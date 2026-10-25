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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 管理随机 Refresh Token 会话的哈希存储、轮换、重放撤销和安全事件。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthSessionService {
    /** 认证数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** Refresh Token HMAC 摘要与随机令牌工具。 */
    private final AuthCryptography cryptography;
    /** 短时 RS256 Access Token 签发服务。 */
    private final LocalTokenIssuer tokenIssuer;
    /** Refresh Session 策略。 */
    private final FoundationProperties.Authentication settings;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建认证会话服务。
     * @param jdbc 数据库访问模板
     * @param cryptography 认证秘密处理服务
     * @param tokenIssuer 本地 JWT 签发器
     * @param properties 全局会话策略
     * @param clock 可替换 UTC 时钟
     */
    public AuthSessionService(JdbcTemplate jdbc, AuthCryptography cryptography, LocalTokenIssuer tokenIssuer,
                              FoundationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.cryptography = cryptography;
        this.tokenIssuer = tokenIssuer;
        this.settings = properties.getAuthentication();
        this.clock = clock;
    }

    /**
     * 为已认证账号创建新 WEB 会话，Refresh Token 只以 HMAC 摘要持久化。
     * @param accountId 已确认 ACTIVE 的账号编号
     * @return Access Token、一次性可交付 Refresh Token 和有效期
     */
    @Transactional
    public SessionTokens create(long accountId) {
        String sessionId = UUID.randomUUID().toString();
        String familyId = UUID.randomUUID().toString();
        String refreshToken = cryptography.randomToken();
        Instant now = clock.instant();
        AuthorizationContext context = personalContext(1);
        insertSession(sessionId, familyId, accountId, refreshToken, context, now.plus(settings.getRefreshSessionTtl()), now);
        return new SessionTokens(tokenIssuer.issue(accountId, sessionId, context.type(), context.tenantId(),
                context.permissions(), context.version()), refreshToken,
                now.plus(settings.getAccessTokenTtl()));
    }

    /**
     * 原子轮换 Refresh Token；旧值再次出现时提交整个会话族撤销后返回统一认证失败。
     * @param refreshToken Cookie 中的不透明令牌
     * @return 新的短期访问令牌和 Refresh Token
     * @throws BusinessException 无效、已撤销、过期令牌或检测到重放
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public SessionTokens rotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        String tokenHash = cryptography.hash("refresh:v1", refreshToken);
        SessionRow session = jdbc.query("""
                SELECT id, family_id, account_id, client_type, context_type, tenant_id, context_version,
                       expires_at, rotated_at, revoked_at
                FROM auth_refresh_session WHERE token_hash = ? FOR UPDATE
                """, result -> result.next() ? new SessionRow(result.getString("id"), result.getString("family_id"),
                result.getLong("account_id"), result.getString("client_type"), result.getString("context_type"),
                result.getObject("tenant_id", Long.class), result.getInt("context_version"),
                result.getTimestamp("expires_at").toInstant(), instant(result.getTimestamp("rotated_at")),
                instant(result.getTimestamp("revoked_at"))) : null, tokenHash);
        if (session == null) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        if (session.rotatedAt() != null) {
            revokeFamily(session.familyId(), true);
            recordEvent(session.accountId(), "REFRESH_REUSE", "DENIED", null);
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        Instant now = clock.instant();
        String accountStatus = accountStatus(session.accountId());
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now) || !"ACTIVE".equals(accountStatus)) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        AuthorizationContext context = resolveContext(session.accountId(), session.contextType(),
                session.tenantId(), session.contextVersion());
        jdbc.update("UPDATE auth_refresh_session SET rotated_at = ?, last_used_at = ? WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), session.id());
        String nextId = UUID.randomUUID().toString();
        String nextToken = cryptography.randomToken();
        insertSession(nextId, session.familyId(), session.accountId(), nextToken, context,
                now.plus(settings.getRefreshSessionTtl()), now);
        return new SessionTokens(tokenIssuer.issue(session.accountId(), nextId, context.type(), context.tenantId(),
                context.permissions(), context.version()), nextToken,
                now.plus(settings.getAccessTokenTtl()));
    }

    /**
     * 将当前浏览器会话切换到已审核组织，并立即令旧上下文 Access Token 失效。
     * @param accountId 当前令牌中的账号编号
     * @param sessionId 当前令牌绑定的刷新会话编号
     * @param organizationId 请求选择的组织编号
     * @return 新上下文访问令牌；Refresh Cookie 保持不变
     * @throws BusinessException 账号、会话、组织或成员状态不允许进入
     */
    @Transactional
    public SessionTokens selectOrganization(long accountId, String sessionId, long organizationId) {
        if (accountId <= 0 || sessionId == null || sessionId.isBlank() || organizationId <= 0) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        SessionRow session = jdbc.query("""
                SELECT id, family_id, account_id, client_type, context_type, tenant_id, context_version,
                       expires_at, rotated_at, revoked_at
                FROM auth_refresh_session WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new SessionRow(result.getString("id"), result.getString("family_id"),
                result.getLong("account_id"), result.getString("client_type"), result.getString("context_type"),
                result.getObject("tenant_id", Long.class), result.getInt("context_version"),
                result.getTimestamp("expires_at").toInstant(), instant(result.getTimestamp("rotated_at")),
                instant(result.getTimestamp("revoked_at"))) : null, sessionId);
        Instant now = clock.instant();
        if (session == null || session.accountId() != accountId || session.rotatedAt() != null
                || session.revokedAt() != null || !session.expiresAt().isAfter(now)
                || !"ACTIVE".equals(accountStatus(accountId))) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        AuthorizationContext context = organizationContext(accountId, organizationId, session.contextVersion() + 1);
        int updated = jdbc.update("""
                UPDATE auth_refresh_session
                SET context_type = 'ORGANIZATION', tenant_id = ?, context_version = ?, last_used_at = ?
                WHERE id = ? AND account_id = ? AND rotated_at IS NULL AND revoked_at IS NULL
                """, organizationId, context.version(), Timestamp.from(now), sessionId, accountId);
        if (updated != 1) {
            throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
        }
        recordEvent(accountId, "CONTEXT_SWITCH", "SUCCESS", null);
        return new SessionTokens(tokenIssuer.issue(accountId, sessionId, context.type(), context.tenantId(),
                context.permissions(), context.version()), null, now.plus(settings.getAccessTokenTtl()));
    }

    /**
     * 撤销当前 Refresh Token；未知令牌按幂等登出处理。
     * @param refreshToken 当前浏览器 Cookie 值
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        jdbc.update("UPDATE auth_refresh_session SET revoked_at = COALESCE(revoked_at, ?) WHERE token_hash = ?",
                Timestamp.from(clock.instant()), cryptography.hash("refresh:v1", refreshToken));
    }

    /**
     * 撤销指定账号全部 Refresh Session，供密码找回和平台禁用流程调用。
     * @param accountId 账号编号
     */
    @Transactional
    public void revokeAll(long accountId) {
        jdbc.update("UPDATE auth_refresh_session SET revoked_at = COALESCE(revoked_at, ?) WHERE account_id = ?",
                Timestamp.from(clock.instant()), accountId);
    }

    /**
     * 判断 Access Token 所属 Refresh Session 是否仍为活动状态。
     * @param sessionId JWT session_id 声明
     * @return 会话可刷新且未过期时为 true
     */
    public boolean isActive(String sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth_refresh_session
                WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP)
                """, Boolean.class, sessionId));
    }

    /**
     * 在每次本地 Access Token 验证时比对会话上下文版本并重新检查当前组织授权。
     * @param sessionId JWT 绑定的刷新会话
     * @param accountId JWT 主体账号
     * @param contextType JWT 上下文类型
     * @param tenantId JWT 组织编号，个人上下文为空
     * @param contextVersion JWT 中的授权版本
     * @return 当前会话、账号、组织和成员全部仍有效时为 true
     */
    public boolean isValidAccessContext(String sessionId, long accountId, String contextType,
                                        Long tenantId, int contextVersion) {
        if (sessionId == null || accountId <= 0 || contextVersion < 1
                || !("PERSONAL".equals(contextType) && tenantId == null
                || "ORGANIZATION".equals(contextType) && tenantId != null && tenantId > 0)) {
            return false;
        }
        Boolean sessionMatches = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM auth_refresh_session s
                    JOIN auth_account a ON a.id = s.account_id
                    WHERE s.id = ? AND s.account_id = ? AND a.status = 'ACTIVE'
                      AND s.context_type = ? AND s.tenant_id IS NOT DISTINCT FROM ?
                      AND s.context_version = ? AND s.rotated_at IS NULL AND s.revoked_at IS NULL
                      AND s.expires_at > CURRENT_TIMESTAMP
                )
                """, Boolean.class, sessionId, accountId, contextType, tenantId, contextVersion);
        if (!Boolean.TRUE.equals(sessionMatches)) {
            return false;
        }
        if ("PERSONAL".equals(contextType)) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM business_membership m
                    JOIN business_organization o ON o.id = m.organization_id
                    WHERE m.organization_id = ? AND m.tenant_id = ? AND m.actor_id = ?
                      AND m.status = 'ACTIVE' AND o.status = 'APPROVED' AND o.tenant_id = o.id
                )
                """, Boolean.class, tenantId, tenantId, accountId));
    }

    /**
     * 查询账号安全状态用于会话签发门禁。
     * @param accountId 账号编号
     * @return ACTIVE、LOCKED、DISABLED 或 null
     */
    private String accountStatus(long accountId) {
        return jdbc.query("SELECT status FROM auth_account WHERE id = ?", result -> result.next() ? result.getString(1) : null,
                accountId);
    }

    /**
     * 保存新会话，只写 Refresh Token HMAC 摘要而不保存原始令牌。
     * @param id 当前轮换节点 ID
     * @param familyId 登录时创建的会话族 ID
     * @param accountId 账号编号
     * @param rawToken 仅本次响应交付的 Refresh Token
     * @param expiresAt 会话过期时间
     * @param now 当前时间
     */
    private void insertSession(String id, String familyId, long accountId, String rawToken,
                               AuthorizationContext context, Instant expiresAt,
                               Instant now) {
        jdbc.update("""
                INSERT INTO auth_refresh_session(id, account_id, family_id, token_hash, client_type,
                    context_type, tenant_id, context_version, expires_at, created_at)
                VALUES (?, ?, ?, ?, 'WEB', ?, ?, ?, ?, ?)
                """, id, accountId, familyId, cryptography.hash("refresh:v1", rawToken), context.type(),
                context.tenantId(), context.version(), Timestamp.from(expiresAt), Timestamp.from(now));
    }

    /**
     * 从当前组织和成员表重新计算访问权限，不将客户端提供的角色写入会话。
     * @param accountId 当前已认证账号
     * @param organizationId 目标组织
     * @param version 新的会话上下文版本
     * @return 已通过组织准入和成员授权检查的组织权限快照
     * @throws BusinessException 组织未审核通过或成员不再 ACTIVE
     */
    private AuthorizationContext organizationContext(long accountId, long organizationId, int version) {
        String[] roles = jdbc.query("""
                SELECT m.member_role, o.organization_role
                FROM business_membership m
                JOIN business_organization o ON o.id = m.organization_id
                WHERE m.organization_id = ? AND m.tenant_id = ? AND m.actor_id = ?
                  AND m.status = 'ACTIVE' AND o.status = 'APPROVED' AND o.tenant_id = o.id
                """, result -> result.next() ? new String[]{result.getString("member_role"),
                result.getString("organization_role")} : null, organizationId, organizationId, accountId);
        if (roles == null) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        if (("SUPPLIER_BIDDER".equals(roles[0]) && !"LABOR_SUPPLIER".equals(roles[1]))
                || ("DEMAND_SELECTOR".equals(roles[0]) && !"EMPLOYER".equals(roles[1]))) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        Set<String> permissions = permissions(roles[0], roles[1]);
        return new AuthorizationContext("ORGANIZATION", organizationId, version, permissions);
    }

    /**
     * 根据首批组织成员岗位和组织角色计算最小业务权限集合。
     * @param memberRole 已审核成员岗位
     * @param organizationRole 组织业务角色
     * @return 不可变权限集合
     */
    private Set<String> permissions(String memberRole, String organizationRole) {
        Set<String> result = new HashSet<>();
        result.add("organization:demands:read");
        switch (memberRole) {
            case "ORGANIZATION_ADMIN" -> {
                result.add("organization:organizations:write");
                result.add("organization:members:manage");
                result.add("organization:membership:request");
                if ("EMPLOYER".equals(organizationRole)) {
                    result.addAll(Set.of("organization:demands:write", "organization:demands:publish",
                            "organization:bids:select"));
                } else if ("LABOR_SUPPLIER".equals(organizationRole)) {
                    result.add("organization:bids:write");
                }
            }
            case "DEMAND_EDITOR" -> result.add("organization:demands:write");
            case "DEMAND_PUBLISHER" -> result.add("organization:demands:publish");
            case "SUPPLIER_BIDDER" -> result.add("organization:bids:write");
            case "DEMAND_SELECTOR" -> result.add("organization:bids:select");
            default -> throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return Set.copyOf(result);
    }

    /** 创建只允许开办组织和申请加入的个人账号上下文。 */
    private AuthorizationContext personalContext(int version) {
        return new AuthorizationContext("PERSONAL", null, version,
                Set.of("organization:create", "organization:membership:request"));
    }

    /**
     * 解析持久会话上下文并核实组织成员状态，用于 Refresh Token 轮换。
     * @param accountId 会话所属账号
     * @param contextType 当前会话上下文类型
     * @param tenantId 当前会话组织
     * @param version 当前上下文版本
     * @return 已核验上下文权限
     */
    private AuthorizationContext resolveContext(long accountId, String contextType, Long tenantId, int version) {
        if ("PERSONAL".equals(contextType) && tenantId == null) {
            return personalContext(version);
        }
        if ("ORGANIZATION".equals(contextType) && tenantId != null) {
            return organizationContext(accountId, tenantId, version);
        }
        throw new BusinessException(PlatformError.AUTHENTICATION_FAILED);
    }

    /**
     * 撤销会话族内所有 Refresh Token，并标记是否因旧值重放触发。
     * @param familyId 登录会话族编号
     * @param reuseDetected 是否记录重放证据
     */
    private void revokeFamily(String familyId, boolean reuseDetected) {
        jdbc.update("""
                UPDATE auth_refresh_session SET revoked_at = COALESCE(revoked_at, ?), reuse_detected = ?
                WHERE family_id = ?
                """, Timestamp.from(clock.instant()), reuseDetected, familyId);
    }

    /**
     * 写入不含令牌或密码的认证安全事件。
     * @param accountId 账号编号，可为空
     * @param type 固定事件类型
     * @param outcome 受限结果值
     * @param subjectHash 脱敏身份摘要，可为空
     */
    private void recordEvent(Long accountId, String type, String outcome, String subjectHash) {
        jdbc.update("INSERT INTO auth_security_event(account_id, event_type, outcome, subject_hash) VALUES (?, ?, ?, ?)",
                accountId, type, outcome, subjectHash);
    }

    /**
     * 将可空数据库时间转换为不可变时间点。
     * @param timestamp SQL 时间戳
     * @return UTC 时间点或 null
     */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 一次会话签发或刷新要返回给受信 Web 控制器的令牌材料。 */
    public record SessionTokens(String accessToken, String refreshToken, Instant accessExpiresAt) {
        /**
         * @param accessToken 短期 RS256 JWT，仅由调用方置于内存或响应 JSON
         * @param refreshToken 不透明刷新值，仅由控制器写入 HttpOnly Cookie
         * @param accessExpiresAt Access Token 的失效时间
         */
    }

    /** 已由服务端验证的会话权限上下文。 */
    private record AuthorizationContext(String type, Long tenantId, int version, Set<String> permissions) {
        /**
         * @param type PERSONAL 或 ORGANIZATION
         * @param tenantId 当前组织编号，个人上下文为空
         * @param version 会话当前的授权上下文版本
         * @param permissions 服务端根据当前成员岗位计算的权限
         */
    }

    /** Refresh Session 轮换所需的数据库快照。 */
    private record SessionRow(String id, String familyId, long accountId, String clientType, String contextType,
                              Long tenantId, int contextVersion, Instant expiresAt, Instant rotatedAt,
                              Instant revokedAt) {
        /**
         * @param id 会话节点编号
         * @param familyId 会话族编号
         * @param accountId 账号编号
         * @param clientType 建立会话的客户端类型
         * @param contextType 当前会话上下文
         * @param tenantId 当前选定组织，可为空
         * @param contextVersion 当前会话授权版本
         * @param expiresAt Refresh Token 失效时间
         * @param rotatedAt 旧令牌轮换时间，可为空
         * @param revokedAt 主动或安全撤销时间，可为空
         */
    }
}
