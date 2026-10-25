package com.company.labor.modules.auth;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** 编排注册、短信/密码登录、密码找回和安全事件，账号状态与组织授权保持分离。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthenticationService {
    /** 认证数据访问入口。 */
    private final JdbcTemplate jdbc;
    /** 持久化短信挑战与原子验证码消费。 */
    private final AuthChallengeService challenges;
    /** 手机号和安全值摘要、手机号加密。 */
    private final AuthCryptography cryptography;
    /** 密码慢哈希与密码政策。 */
    private final PasswordCredentialEncoder passwords;
    /** Refresh Session 生命周期。 */
    private final AuthSessionService sessions;
    /** 认证时限和失败窗口策略。 */
    private final FoundationProperties.Authentication settings;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建手机号认证用例服务。
     * @param jdbc 认证数据库访问模板
     * @param challenges 短信挑战服务
     * @param cryptography 认证密码学工具
     * @param passwords 密码策略和 Argon2id 编码器
     * @param sessions Refresh Session 服务
     * @param properties 全局认证策略
     * @param clock 可替换 UTC 时钟
     */
    public AuthenticationService(JdbcTemplate jdbc, AuthChallengeService challenges, AuthCryptography cryptography,
                                 PasswordCredentialEncoder passwords, AuthSessionService sessions,
                                 FoundationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.challenges = challenges;
        this.cryptography = cryptography;
        this.passwords = passwords;
        this.sessions = sessions;
        this.settings = properties.getAuthentication();
        this.clock = clock;
    }

    /**
     * 发起注册短信，响应不区分手机号是否已经注册。
     * @param phone 手机号
     * @param remoteAddress 服务端来源地址
     * @return 可公开短信挑战信息
     */
    public AuthChallengeService.ChallengeStart startRegistration(String phone, String remoteAddress) {
        return challenges.start(phone, "REGISTER", remoteAddress);
    }

    /**
     * 原子消费注册挑战并建立 ACTIVE 账号、手机号身份、密码、协议记录及首个会话。
     * @param request 已校验的注册资料
     * @param remoteAddress 服务端来源地址
     * @return 已认证会话，不包含组织权限
     * @throws BusinessException 验证码无效、手机号已占用或注册凭据被拒绝
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResult register(AuthDtos.RegisterRequest request, String remoteAddress) {
        String passwordHash = passwords.encode(request.password());
        String phoneHash = cryptography.hashPhone(request.phone());
        AuthChallengeService.VerifiedChallenge challenge = challenges.consume(request.challengeId(), "REGISTER", request.code());
        if (!cryptography.matches(challenge.subjectHash(), phoneHash)) {
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        Instant now = clock.instant();
        Long accountId = jdbc.query("""
                INSERT INTO auth_account(status, display_name, phone_verified_at, created_at, updated_at)
                VALUES ('ACTIVE', ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id
                """, result -> result.next() ? result.getLong(1) : null,
                blankToNull(request.displayName()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (accountId == null) {
            recordEvent(null, "REGISTER", "DENIED", phoneHash, remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        Long identityId = jdbc.query("""
                INSERT INTO auth_login_identity(account_id, provider, subject_hash, subject_ciphertext, verified_at)
                VALUES (?, 'PHONE', ?, ?, ?) ON CONFLICT (provider, subject_hash) DO NOTHING RETURNING id
                """, result -> result.next() ? result.getLong(1) : null,
                accountId, phoneHash, cryptography.encryptPhone(request.phone()), Timestamp.from(now));
        if (identityId == null) {
            jdbc.update("DELETE FROM auth_account WHERE id = ?", accountId);
            recordEvent(null, "REGISTER", "DENIED", phoneHash, remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        jdbc.update("""
                INSERT INTO auth_credential(account_id, credential_type, password_hash, password_updated_at)
                VALUES (?, 'PASSWORD', ?, ?)
                """, accountId, passwordHash, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO auth_consent_record(account_id, consent_type, version, ip_hash)
                VALUES (?, 'TERMS', ?, ?), (?, 'PRIVACY', ?, ?)
                """, accountId, request.termsVersion(), hashIp(remoteAddress),
                accountId, request.privacyVersion(), hashIp(remoteAddress));
        recordEvent(accountId, "REGISTER", "SUCCESS", phoneHash, remoteAddress);
        return new AuthResult(summary(accountId), sessions.create(accountId));
    }

    /**
     * 发起短信登录挑战，对不存在手机号也返回同样的响应结构。
     * @param phone 手机号
     * @param remoteAddress 服务端来源地址
     * @return 可公开挑战时间和编号
     */
    public AuthChallengeService.ChallengeStart startSmsLogin(String phone, String remoteAddress) {
        return challenges.start(phone, "LOGIN", remoteAddress);
    }

    /**
     * 通过一次性短信挑战建立最小权限个人会话。
     * @param challengeId 短信挑战编号
     * @param code 六位验证码
     * @param remoteAddress 服务端来源地址
     * @return 已认证个人会话
     * @throws BusinessException 验证码错误或账号不存在、锁定、禁用
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResult loginBySms(String challengeId, String code, String remoteAddress) {
        AuthChallengeService.VerifiedChallenge challenge = challenges.consume(challengeId, "LOGIN", code);
        if (challenge.accountId() == null || !activateAfterTemporaryLock(challenge.accountId())) {
            recordEvent(challenge.accountId(), "SMS_LOGIN", "FAILED", challenge.subjectHash(), remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        recordEvent(challenge.accountId(), "SMS_LOGIN", "SUCCESS", challenge.subjectHash(), remoteAddress);
        return new AuthResult(summary(challenge.accountId()), sessions.create(challenge.accountId()));
    }

    /**
     * 校验手机号密码并在失败窗口内计数，达到阈值后设置临时账号锁定。
     * @param phone 中国大陆手机号
     * @param password 明文登录输入，仅在内存参与 Argon2id 校验
     * @param remoteAddress 服务端来源地址
     * @return 已认证个人会话
     * @throws BusinessException 凭据不正确、账号锁定或账号禁用，统一外部错误
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResult loginByPassword(String phone, String password, String remoteAddress) {
        String phoneHash = cryptography.hashPhone(phone);
        AccountCredential credential = jdbc.query("""
                SELECT a.id, a.status, a.locked_until, c.password_hash
                FROM auth_login_identity i JOIN auth_account a ON a.id = i.account_id
                JOIN auth_credential c ON c.account_id = a.id AND c.credential_type = 'PASSWORD'
                WHERE i.provider = 'PHONE' AND i.subject_hash = ? AND i.status = 'ACTIVE'
                FOR UPDATE OF a
                """, result -> result.next() ? new AccountCredential(result.getLong("id"), result.getString("status"),
                instant(result.getTimestamp("locked_until")), result.getString("password_hash")) : null, phoneHash);
        if (credential == null) {
            recordEvent(null, "PASSWORD_LOGIN", "FAILED", phoneHash, remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        if (!activateAfterTemporaryLock(credential.accountId()) || !passwords.matches(password, credential.passwordHash())) {
            recordFailedPassword(credential.accountId(), phoneHash, remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        jdbc.update("UPDATE auth_account SET failed_login_attempts = 0, locked_until = NULL, updated_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), credential.accountId());
        recordEvent(credential.accountId(), "PASSWORD_LOGIN", "SUCCESS", phoneHash, remoteAddress);
        return new AuthResult(summary(credential.accountId()), sessions.create(credential.accountId()));
    }

    /**
     * 发起密码找回短信挑战，统一响应避免账号枚举。
     * @param phone 手机号
     * @param remoteAddress 服务端来源地址
     * @return 可公开短信挑战信息
     */
    public AuthChallengeService.ChallengeStart startPasswordReset(String phone, String remoteAddress) {
        return challenges.start(phone, "PASSWORD_RESET", remoteAddress);
    }

    /**
     * 验证找回短信并发行数据库只存摘要的一次性重置票据。
     * @param challengeId 找回短信挑战
     * @param code 六位验证码
     * @param remoteAddress 服务端来源地址
     * @return 重置票据 ID、一次性秘密和失效时间
     * @throws BusinessException 挑战无效或没有可恢复账号
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthDtos.ResetTicketResponse verifyPasswordReset(String challengeId, String code, String remoteAddress) {
        AuthChallengeService.VerifiedChallenge challenge = challenges.consume(challengeId, "PASSWORD_RESET", code);
        if (challenge.accountId() == null || !activateAfterTemporaryLock(challenge.accountId())) {
            recordEvent(challenge.accountId(), "PASSWORD_RESET_VERIFY", "FAILED", challenge.subjectHash(), remoteAddress);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        String ticketId = UUID.randomUUID().toString();
        String resetToken = cryptography.randomToken();
        Instant expiresAt = clock.instant().plus(settings.getPasswordResetTicketTtl());
        jdbc.update("""
                INSERT INTO auth_password_reset_ticket(id, account_id, challenge_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, ticketId, challenge.accountId(), challenge.id(), cryptography.hash("reset-ticket:v1", resetToken),
                Timestamp.from(expiresAt));
        recordEvent(challenge.accountId(), "PASSWORD_RESET_VERIFY", "SUCCESS", challenge.subjectHash(), remoteAddress);
        return new AuthDtos.ResetTicketResponse(ticketId, resetToken, expiresAt);
    }

    /**
     * 原子消费有效重置票据，更新 Argon2id 凭据版本并撤销账号全部 Refresh Session。
     * @param request 重置票据及新密码
     * @param remoteAddress 服务端来源地址
     * @throws BusinessException 票据无效、过期、重放或账号状态不允许重置
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void completePasswordReset(AuthDtos.CompleteResetRequest request, String remoteAddress) {
        String encodedPassword = passwords.encode(request.newPassword());
        ResetTicket ticket = jdbc.query("""
                SELECT id, account_id, token_hash, expires_at, consumed_at
                FROM auth_password_reset_ticket WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new ResetTicket(result.getString("id"), result.getLong("account_id"),
                result.getString("token_hash"), result.getTimestamp("expires_at").toInstant(),
                instant(result.getTimestamp("consumed_at"))) : null, request.ticketId());
        if (ticket == null || ticket.consumedAt() != null || !ticket.expiresAt().isAfter(clock.instant())
                || !cryptography.matches(ticket.tokenHash(), cryptography.hash("reset-ticket:v1", request.resetToken()))
                || !activateAfterTemporaryLock(ticket.accountId())) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        jdbc.update("UPDATE auth_password_reset_ticket SET consumed_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), ticket.id());
        jdbc.update("""
                UPDATE auth_credential SET password_hash = ?, password_updated_at = ?
                WHERE account_id = ? AND credential_type = 'PASSWORD'
                """, encodedPassword, Timestamp.from(clock.instant()), ticket.accountId());
        jdbc.update("""
                UPDATE auth_account SET credential_version = credential_version + 1, failed_login_attempts = 0,
                    locked_until = NULL, status = 'ACTIVE', updated_at = ? WHERE id = ?
                """, Timestamp.from(clock.instant()), ticket.accountId());
        sessions.revokeAll(ticket.accountId());
        recordEvent(ticket.accountId(), "PASSWORD_RESET", "SUCCESS", null, remoteAddress);
    }

    /**
     * 返回登录账号的非敏感状态摘要。
     * @param accountId 来自已验证 Access Token 的账号编号
     * @return 账号编号、状态和可选显示名
     * @throws BusinessException 账号不存在时按不可访问资源处理
     */
    public AuthDtos.AccountSummary currentAccount(long accountId) {
        AuthDtos.AccountSummary account = summary(accountId);
        if (account == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return account;
    }

    /**
     * 校验账号状态，并在临时锁到期后自动恢复 ACTIVE；平台禁用不会自动解除。
     * @param accountId 账号编号
     * @return 账号可执行身份认证时为 true
     */
    private boolean activateAfterTemporaryLock(long accountId) {
        AccountState state = jdbc.query("SELECT status, locked_until FROM auth_account WHERE id = ? FOR UPDATE",
                result -> result.next() ? new AccountState(result.getString(1), instant(result.getTimestamp(2))) : null,
                accountId);
        if (state == null || "DISABLED".equals(state.status())) {
            return false;
        }
        if ("LOCKED".equals(state.status())) {
            if (state.lockedUntil() == null || state.lockedUntil().isAfter(clock.instant())) {
                return false;
            }
            jdbc.update("UPDATE auth_account SET status = 'ACTIVE', failed_login_attempts = 0, locked_until = NULL WHERE id = ?",
                    accountId);
        }
        return true;
    }

    /**
     * 记录密码失败，并按可查询安全事件的滚动时间窗自动暂时锁定账号。
     * @param accountId 账号编号
     * @param phoneHash 手机号摘要
     * @param remoteAddress 来源地址
     */
    private void recordFailedPassword(long accountId, String phoneHash, String remoteAddress) {
        Instant now = clock.instant();
        recordEvent(accountId, "PASSWORD_LOGIN", "FAILED", phoneHash, remoteAddress);
        Integer recentFailures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_security_event
                WHERE account_id = ? AND event_type = 'PASSWORD_LOGIN' AND outcome = 'FAILED' AND created_at >= ?
                """, Integer.class, accountId, Timestamp.from(now.minus(settings.getLoginFailureWindow())));
        if (recentFailures != null && recentFailures >= settings.getLoginFailureLimit()) {
            jdbc.update("""
                    UPDATE auth_account SET status = 'LOCKED', failed_login_attempts = ?, locked_until = ?, updated_at = ?
                    WHERE id = ? AND status <> 'DISABLED'
                    """, recentFailures, Timestamp.from(now.plus(settings.getLoginLockDuration())), Timestamp.from(now), accountId);
            recordEvent(accountId, "ACCOUNT_LOCKED", "DENIED", phoneHash, remoteAddress);
        }
    }

    /**
     * 读取账号当前可公开摘要，不包含手机号、凭据或内部安全字段。
     * @param accountId 全局账号 ID
     * @return 账号编号、状态和显示名
     */
    private AuthDtos.AccountSummary summary(long accountId) {
        return jdbc.query("SELECT id, status, display_name FROM auth_account WHERE id = ?",
                result -> result.next() ? new AuthDtos.AccountSummary(result.getLong("id"), result.getString("status"),
                        result.getString("display_name")) : null, accountId);
    }

    /**
     * 保存不包含秘密的认证审计事件。
     * @param accountId 账号 ID，可为空
     * @param type 固定事件类别
     * @param outcome 受限结果
     * @param subjectHash 手机号摘要，可为空
     * @param remoteAddress 来源地址，仅保存 HMAC 摘要
     */
    private void recordEvent(Long accountId, String type, String outcome, String subjectHash, String remoteAddress) {
        jdbc.update("""
                INSERT INTO auth_security_event(account_id, event_type, outcome, subject_hash, ip_hash)
                VALUES (?, ?, ?, ?, ?)
                """, accountId, type, outcome, subjectHash, hashIp(remoteAddress));
    }

    /**
     * 对来源地址作不可逆摘要；缺失地址不伪造标识。
     * @param address 请求来源地址
     * @return 带用途标签的 HMAC 摘要或 null
     */
    private String hashIp(String address) {
        return address == null || address.isBlank() ? null : cryptography.hash("client-ip:v1", address);
    }

    /**
     * 将用户可空显示名去空白后转为空值或非空字符串。
     * @param value 原始显示名
     * @return 清理后的显示名
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 将可空 SQL 时间转换为 UTC 时间点。
     * @param timestamp SQL 时间戳
     * @return 时间点或 null
     */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 已认证成功后的会话和账号摘要。 */
    public record AuthResult(AuthDtos.AccountSummary account, AuthSessionService.SessionTokens tokens) {
        /**
         * @param account 安全状态摘要
         * @param tokens 短期访问令牌及待写入 HttpOnly Cookie 的刷新令牌
         */
    }

    /** 登录所需的数据库凭据快照。 */
    private record AccountCredential(long accountId, String status, Instant lockedUntil, String passwordHash) {
        /**
         * @param accountId 账号编号
         * @param status 账号状态
         * @param lockedUntil 临时锁定截止时间
         * @param passwordHash Argon2id 编码凭据
         */
    }

    /** 账号锁定状态快照。 */
    private record AccountState(String status, Instant lockedUntil) {
        /**
         * @param status 账号当前状态
         * @param lockedUntil 暂时锁定截止时间
         */
    }

    /** 一次性重置凭据的持久化快照。 */
    private record ResetTicket(String id, long accountId, String tokenHash, Instant expiresAt, Instant consumedAt) {
        /**
         * @param id 票据编号
         * @param accountId 票据所属账号
         * @param tokenHash 一次性秘密摘要
         * @param expiresAt 票据失效时间
         * @param consumedAt 消费时间，未消费时为空
         */
    }
}
