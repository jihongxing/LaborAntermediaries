package com.company.labor.modules.auth;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** 持久化短信验证码挑战与限流计数；消费使用行锁，发送配额使用事务级咨询锁原子保护。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthChallengeService {
    /** 认证数据访问模板。 */
    private final JdbcTemplate jdbc;
    /** HMAC 摘要和短信随机码服务。 */
    private final AuthCryptography cryptography;
    /** profile 限定的短信网关。 */
    private final SmsGateway smsGateway;
    /** 认证参数与窗口限制。 */
    private final FoundationProperties.Authentication settings;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建验证码挑战服务。
     * @param jdbc 认证数据库访问入口
     * @param cryptography 认证摘要和随机秘密服务
     * @param smsGateway 当前 profile 的短信发送实现
     * @param properties 全局认证策略
     * @param clock 可替换 UTC 时钟
     */
    public AuthChallengeService(JdbcTemplate jdbc, AuthCryptography cryptography, SmsGateway smsGateway,
                                FoundationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.cryptography = cryptography;
        this.smsGateway = smsGateway;
        this.settings = properties.getAuthentication();
        this.clock = clock;
    }

    /**
     * 创建短信挑战并应用手机号冷却、手机号小时限额和来源 IP 小时限额。
     * @param phone 中国大陆手机号
     * @param purpose REGISTER、LOGIN 或 PASSWORD_RESET
     * @param remoteAddress 请求来源 IP，由服务端连接信息取得
     * @return 可公开的挑战编号、有效期和重发时间
     * @throws BusinessException 输入无效或触发统一安全限流
     */
    @Transactional
    public ChallengeStart start(String phone, String purpose, String remoteAddress) {
        if (!purpose.matches("REGISTER|LOGIN|PASSWORD_RESET")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        String normalizedPhone = PhoneNumberNormalizer.normalize(phone);
        String phoneHash = cryptography.hashPhone(normalizedPhone);
        String ipHash = remoteAddress == null || remoteAddress.isBlank()
                ? null : cryptography.hash("client-ip:v1", remoteAddress);
        Instant now = clock.instant();
        acquireRateLimitLocks(phoneHash, ipHash);
        if (countSince("subject_hash", phoneHash, now.minusSeconds(3600)) >= settings.getSmsPhoneHourlyLimit()
                || (ipHash != null && countSince("created_ip_hash", ipHash, now.minusSeconds(3600))
                >= settings.getSmsIpHourlyLimit())) {
            throw new BusinessException(PlatformError.AUTH_RATE_LIMITED);
        }
        Instant resendAfter = jdbc.query("""
                SELECT resend_after FROM auth_verification_challenge
                WHERE subject_hash = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1
                """, result -> result.next() ? result.getTimestamp(1).toInstant() : null, phoneHash, purpose);
        if (resendAfter != null && resendAfter.isAfter(now)) {
            throw new BusinessException(PlatformError.AUTH_RATE_LIMITED);
        }
        Instant expiresAt = now.plus(settings.getChallengeTtl());
        Instant resendAt = now.plus(settings.getSmsResendCooldown());
        String challengeId = UUID.randomUUID().toString();
        String code = cryptography.verificationCode();
        String codeHash = cryptography.hash("challenge:" + purpose, code);
        Long accountId = findAccountId(phoneHash);
        jdbc.update("""
                INSERT INTO auth_verification_challenge
                    (id, purpose, account_id, subject_hash, code_hash, max_attempts, expires_at, resend_after,
                     created_ip_hash, created_at)
                VALUES (?, ?, ?, ?, ?, 5, ?, ?, ?, ?)
                """, challengeId, purpose, accountId, phoneHash, codeHash,
                Timestamp.from(expiresAt), Timestamp.from(resendAt), ipHash, Timestamp.from(now));
        smsGateway.send(challengeId, normalizedPhone, code, expiresAt);
        return new ChallengeStart(challengeId, expiresAt, resendAt);
    }

    /**
     * 校验用途、时限和验证码并一次性消费挑战；事务行锁拒绝并发重放。
     * @param challengeId 服务端生成的挑战编号
     * @param expectedPurpose 调用流程预期用途
     * @param code 用户提交的六位验证码
     * @return 挑战手机号摘要与关联账号 ID；账号可为空以维持统一匿名响应
     * @throws BusinessException 挑战缺失、用途不匹配、过期、重放或达到尝试上限
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public VerifiedChallenge consume(String challengeId, String expectedPurpose, String code) {
        ChallengeRow challenge = jdbc.query("""
                SELECT id, purpose, account_id, subject_hash, code_hash, state, attempts, max_attempts, expires_at
                FROM auth_verification_challenge WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new ChallengeRow(result.getString("id"), result.getString("purpose"),
                (Long) result.getObject("account_id"), result.getString("subject_hash"), result.getString("code_hash"),
                result.getString("state"), result.getInt("attempts"), result.getInt("max_attempts"),
                result.getTimestamp("expires_at").toInstant()) : null, challengeId);
        if (challenge == null || !challenge.purpose().equals(expectedPurpose) || !challenge.state().equals("ISSUED")) {
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        Instant now = clock.instant();
        if (!challenge.expiresAt().isAfter(now)) {
            jdbc.update("UPDATE auth_verification_challenge SET state = 'EXPIRED' WHERE id = ?", challengeId);
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        String suppliedHash = cryptography.hash("challenge:" + expectedPurpose,
                code == null ? "missing-code" : code);
        if (!cryptography.matches(challenge.codeHash(), suppliedHash)) {
            int attempts = challenge.attempts() + 1;
            String state = attempts >= challenge.maxAttempts() ? "LOCKED" : "ISSUED";
            jdbc.update("UPDATE auth_verification_challenge SET attempts = ?, state = ? WHERE id = ?",
                    attempts, state, challengeId);
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        jdbc.update("UPDATE auth_verification_challenge SET state = 'CONSUMED', verified_at = ?, consumed_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), challengeId);
        return new VerifiedChallenge(challenge.id(), challenge.subjectHash(), challenge.accountId());
    }

    /**
     * 统计给定手机号摘要或来源摘要在时间窗内的发送次数。
     * 列名来自内部固定调用点，不接受请求控制的 SQL 标识符。
     * @param column 固定列 created_ip_hash 或 subject_hash
     * @param value HMAC 摘要
     * @param since 窗口起点
     * @return 窗口内已创建挑战数
     */
    private int countSince(String column, String value, Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_verification_challenge WHERE " + column
                + " = ? AND created_at >= ?", Integer.class, value, Timestamp.from(since));
    }

    /**
     * 在当前事务内串行化相同手机号和来源 IP 的计数及插入，避免并发请求同时读到旧计数后超发。
     * 锁命名空间固定且顺序固定；PostgreSQL 在事务结束时释放锁，锁冲突只会短暂延迟同一来源的发码。
     * @param phoneHash 规范化手机号 HMAC 摘要
     * @param ipHash 来源 IP HMAC 摘要；无法取得来源时为空
     */
    private void acquireRateLimitLocks(String phoneHash, String ipHash) {
        if (ipHash != null) {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                    "auth-sms-ip:" + ipHash);
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "auth-sms-phone:" + phoneHash);
    }

    /**
     * 通过唯一手机号摘要查找活动账号；未知手机号返回空以避免枚举响应。
     * @param phoneHash 规范化手机号的 HMAC 摘要
     * @return 账号编号，未关联时为空
     */
    private Long findAccountId(String phoneHash) {
        return jdbc.query("""
                SELECT account_id FROM auth_login_identity
                WHERE provider = 'PHONE' AND subject_hash = ? AND status = 'ACTIVE'
                """, result -> result.next() ? result.getLong(1) : null, phoneHash);
    }

    /** 通过短信验证且已消费的挑战摘要。 */
    public record VerifiedChallenge(String id, String subjectHash, Long accountId) {
        /**
         * @param id 已消费挑战编号
         * @param subjectHash 规范化手机号摘要
         * @param accountId 已有账号编号，注册时通常为空
         */
    }

    /** 可供匿名客户端使用的短信挑战时间信息。 */
    public record ChallengeStart(String challengeId, Instant expiresAt, Instant resendAfter) {
        /**
         * @param challengeId 服务端随机挑战编号
         * @param expiresAt 验证码的服务端失效时间
         * @param resendAfter 允许再次发送的服务端时间
         */
    }

    /** 数据库挑战行的不可变验证快照。 */
    private record ChallengeRow(String id, String purpose, Long accountId, String subjectHash, String codeHash,
                                String state, int attempts, int maxAttempts, Instant expiresAt) {
        /**
         * @param id 挑战编号
         * @param purpose 挑战用途
         * @param accountId 手机号当前关联账号，可为空
         * @param subjectHash 手机号摘要
         * @param codeHash 验证码摘要
         * @param state 持久化挑战状态
         * @param attempts 已失败次数
         * @param maxAttempts 允许最大验证次数
         * @param expiresAt 挑战失效时间
         */
    }
}
