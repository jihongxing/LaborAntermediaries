package com.company.labor.modules.auth;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 管理已登录账号显式绑定外部身份的一次性短时流程，不执行账号合并或无会话认领。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class IdentityBindingService {
    /** 一次性绑定票据有效期；必须短于常规登录会话。 */
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);
    /** 认证数据访问模板。 */
    private final JdbcTemplate jdbc;
    /** 外部身份、票据和审计摘要工具。 */
    private final AuthCryptography cryptography;
    /** 只包含显式配置的真实提供商适配器。 */
    private final Map<String, IdentityProvider> providers;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建显式绑定服务；无外部提供商 Bean 时保留空注册表并对绑定失败关闭。
     * @param jdbc 认证数据访问模板
     * @param cryptography 认证摘要和随机秘密工具
     * @param providers 真实外部身份适配器列表
     * @param clock 可替换 UTC 时钟
     */
    public IdentityBindingService(JdbcTemplate jdbc, AuthCryptography cryptography,
                                  java.util.List<IdentityProvider> providers, Clock clock) {
        this.jdbc = jdbc;
        this.cryptography = cryptography;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                IdentityProvider::providerKey, Function.identity()));
        this.clock = clock;
    }

    /**
     * 为当前已登录账号签发只能由同一账号消费一次的短期绑定票据。
     * @param provider 外部身份提供商标识
     * @return 只在本次响应中出现的票据秘密和失效时间
     * @throws BusinessException 当前账号未认证或票据请求不合法
     */
    @Transactional
    public BindingTicket issue(String provider) {
        String normalizedProvider = normalizeProvider(provider);
        if (!providers.containsKey(normalizedProvider)) {
            throw new BusinessException(PlatformError.AUTH_PROVIDER_UNAVAILABLE);
        }
        long accountId = ExecutionContext.require().actorId();
        String ticketId = UUID.randomUUID().toString();
        String token = cryptography.randomToken();
        Instant expiresAt = clock.instant().plus(TICKET_TTL);
        jdbc.update("""
                INSERT INTO auth_identity_binding_ticket(id, account_id, provider, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, ticketId, accountId, normalizedProvider,
                cryptography.hash("identity-binding:" + normalizedProvider, token), Timestamp.from(expiresAt));
        return new BindingTicket(ticketId, token, normalizedProvider, expiresAt);
    }

    /**
     * 验证票据归属并通过提供商 SPI 解析身份，再以唯一约束原子绑定到当前账号。
     * @param request 票据、外部一次性授权码和已登记回调地址
     * @return 仅含提供商标识的绑定结果
     * @throws BusinessException 票据失效、提供商不可用或身份已被其他账号占用
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public BindingResult complete(CompleteBinding request) {
        long accountId = ExecutionContext.require().actorId();
        TicketRow ticket = jdbc.query("""
                SELECT account_id, provider, token_hash, expires_at, consumed_at
                FROM auth_identity_binding_ticket WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new TicketRow(result.getLong("account_id"),
                result.getString("provider"), result.getString("token_hash"),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("consumed_at") == null ? null : result.getTimestamp("consumed_at").toInstant())
                : null, request.ticketId());
        if (ticket == null || ticket.accountId() != accountId || ticket.consumedAt() != null
                || !ticket.expiresAt().isAfter(clock.instant())
                || !ticket.provider().equals(normalizeProvider(request.provider()))
                || !cryptography.matches(ticket.tokenHash(),
                cryptography.hash("identity-binding:" + ticket.provider(), request.ticket()))
                || request.authorizationCode() == null || request.authorizationCode().isBlank()) {
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        IdentityProvider provider = providers.get(ticket.provider());
        if (provider == null) {
            throw new BusinessException(PlatformError.AUTH_PROVIDER_UNAVAILABLE);
        }
        IdentityProvider.VerifiedIdentity identity;
        try {
            identity = provider.exchangeAuthorizationCode(request.authorizationCode(), request.redirectUri());
        } catch (IdentityProvider.IdentityProviderException exception) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        if (!ticket.provider().equals(identity.providerKey())) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        Instant consumedAt = clock.instant();
        int consumed = jdbc.update("""
                UPDATE auth_identity_binding_ticket SET consumed_at = ?
                WHERE id = ? AND account_id = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(consumedAt), request.ticketId(), accountId, Timestamp.from(consumedAt));
        if (consumed != 1) {
            throw new BusinessException(PlatformError.AUTH_CHALLENGE_INVALID);
        }
        String subjectHash = cryptography.hash("external-identity:" + ticket.provider(), identity.subject());
        int inserted = jdbc.update("""
                INSERT INTO auth_login_identity(account_id, provider, subject_hash, verified_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (provider, subject_hash) DO NOTHING
                """, accountId, ticket.provider(), subjectHash, Timestamp.from(clock.instant()));
        if (inserted != 1) {
            throw new BusinessException(PlatformError.AUTH_IDENTITY_CONFLICT);
        }
        jdbc.update("""
                INSERT INTO auth_security_event(account_id, event_type, outcome, subject_hash, created_at)
                VALUES (?, 'IDENTITY_BOUND', 'SUCCESS', ?, ?)
                """, accountId, subjectHash, Timestamp.from(consumedAt));
        return new BindingResult(ticket.provider());
    }

    /**
     * 规范化并限制 provider 名称，避免请求值控制 SQL、日志或 HMAC 用途。
     * @param provider 请求提供商标识
     * @return 大写规范标识
     */
    /**
     * @param provider 请求提供商标识
     * @return 大写规范标识
     * @throws BusinessException provider 格式不符合固定标识语法
     */
    private String normalizeProvider(String provider) {
        if (provider == null || !provider.matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return provider;
    }

    /** 仅返回一次的绑定票据响应。 */
    public record BindingTicket(String ticketId, String ticket, String provider, Instant expiresAt) {
        /**
         * @param ticketId 服务端生成的票据编号
         * @param ticket 只返回一次的随机票据秘密
         * @param provider 固定提供商标识
         * @param expiresAt 十分钟后的失效时间
         */
    }

    /** 绑定外部身份请求；subject 只能由服务端 SPI 从授权码取得。 */
    public record CompleteBinding(String provider, String ticketId, String ticket,
                                   String authorizationCode, String redirectUri) {
        /**
         * @param provider 票据所绑定的提供商
         * @param ticketId 绑定开始响应的票据编号
         * @param ticket 绑定开始响应的一次性票据秘密
         * @param authorizationCode 提供商签发的短时一次性授权码
         * @param redirectUri 与提供商授权请求相同的已登记回调地址
         */
    }

    /** 完成外部身份显式绑定后的最小响应。 */
    public record BindingResult(String provider) {
        /**
         * @param provider 已绑定的提供商标识
         */
    }

    /** 已锁定的绑定票据数据库快照。 */
    private record TicketRow(long accountId, String provider, String tokenHash, Instant expiresAt,
                             Instant consumedAt) {
        /**
         * @param accountId 唯一允许消费该票据的账号编号
         * @param provider 票据预定的提供商
         * @param tokenHash 一次性票据的摘要
         * @param expiresAt 服务端失效时间
         * @param consumedAt 已消费时间；空值表示尚可使用
         */
    }
}
