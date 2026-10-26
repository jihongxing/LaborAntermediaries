package com.company.labor.modules.auth;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 解析实时平台岗位权限并维护受控授权，平台岗位不继承个人或组织成员权限。 */
@Service
public class PlatformAuthorizationService {
    /** 平台账号变更理由的最大存储长度。 */
    private static final int MAX_REASON_LENGTH = 500;
    /** 不允许控制字符进入授权理由或部署操作者标识。 */
    private static final Pattern SAFE_TEXT = Pattern.compile("[^\\p{Cntrl}]{1,500}");
    /** 平台授权与身份状态的数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 成功事件与授权事务共同提交的审计写入器。 */
    private final AuditRecorder audit;
    /** 可替换 UTC 时钟，用于一致地验证授权有效期。 */
    private final Clock clock;

    /**
     * 创建平台授权服务。
     * @param jdbc 数据库访问模板
     * @param audit 追加式审计服务
     * @param clock 可替换的 UTC 时钟
     */
    public PlatformAuthorizationService(JdbcTemplate jdbc, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 受控离线引导首个最小授权管理员；并发执行由事务级 advisory lock 串行化。
     * @param operatorId 部署操作者外部编号
     * @param targetAccountId 已存在且 ACTIVE 的个人账号
     * @param expiresAt 本次授权的明确到期时间
     * @param reason 部署授权理由
     * @return 新平台岗位授权记录编号
     * @throws BusinessException 已初始化、目标账号无效或系统中已有授权记录
     */
    @Transactional
    public long bootstrap(long operatorId, long targetAccountId, Instant expiresAt, String reason) {
        validateOperator(operatorId);
        validateTargetAndExpiry(targetAccountId, expiresAt, reason);
        jdbc.execute("SELECT pg_advisory_xact_lock(714039, 1)");
        boolean initialized = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM platform_authorization_bootstrap)
                    OR EXISTS (SELECT 1 FROM platform_authorization_grant)
                    OR EXISTS (SELECT 1 FROM platform_authorization_change_request)
                    OR EXISTS (SELECT 1 FROM platform_authorization_event)
                """, Boolean.class));
        if (initialized) {
            recordBootstrapFailure(operatorId, targetAccountId, "BOOTSTRAP_ALREADY_INITIALIZED");
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        requireActiveAccount(targetAccountId);
        if (hasActiveMembership(targetAccountId)) {
            recordBootstrapFailure(operatorId, targetAccountId, "BOOTSTRAP_TARGET_HAS_ORGANIZATION_ROLE");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        long grantId = insertGrant(targetAccountId, PlatformAuthorizationRole.AUTHORIZATION_ADMIN, null, expiresAt);
        jdbc.update("""
                INSERT INTO platform_authorization_bootstrap
                    (singleton_id, grant_id, target_account_id, operator_id, reason, expires_at)
                VALUES (1, ?, ?, ?, ?, ?)
                """, grantId, targetAccountId, operatorId, reason.trim(), Timestamp.from(expiresAt));
        jdbc.update("""
                INSERT INTO platform_authorization_event
                    (grant_id, action_code, target_account_id, role_code, operator_id, outcome, reason, expires_at)
                VALUES (?, 'BOOTSTRAP', ?, 'AUTHORIZATION_ADMIN', ?, 'SUCCESS', ?, ?)
                """, grantId, targetAccountId, operatorId, reason.trim(), Timestamp.from(expiresAt));
        audit.recordServiceEvent(operatorId, "platform:authorization:changed", "platform_authorization_grant",
                Long.toString(grantId), "SUCCESS", "BOOTSTRAP");
        return grantId;
    }

    /**
     * 从账号、岗位和到期时间重新解析平台授权；不得使用 JWT 权限快照替代此查询。
     * @param accountId 认证账号编号
     * @return 当前有效的单一平台岗位及其权限，未授权或账号无效时为空
     */
    public AuthorizationSnapshot authorizationForAccount(long accountId) {
        return jdbc.query("""
                SELECT g.role_code, p.permission_code
                FROM platform_authorization_grant g
                JOIN auth_account a ON a.id = g.account_id AND a.status = 'ACTIVE'
                JOIN platform_authorization_role_permission p ON p.role_code = g.role_code
                WHERE g.account_id = ? AND g.revoked_at IS NULL AND g.expires_at > CURRENT_TIMESTAMP
                  AND (g.role_code <> 'AUTHORIZATION_ADMIN' OR NOT EXISTS (
                      SELECT 1 FROM business_membership m WHERE m.actor_id = g.account_id AND m.status = 'ACTIVE'))
                  AND (g.role_code <> 'INDEPENDENT_AUDITOR' OR NOT EXISTS (
                      SELECT 1 FROM business_membership m WHERE m.actor_id = g.account_id AND m.status = 'ACTIVE'))
                ORDER BY p.permission_code
                """, result -> {
            String role = null;
            Set<String> permissions = new java.util.LinkedHashSet<>();
            while (result.next()) {
                String currentRole = result.getString("role_code");
                if (role != null && !role.equals(currentRole)) {
                    throw new IllegalStateException("An account has more than one active platform role");
                }
                role = currentRole;
                permissions.add(result.getString("permission_code"));
            }
            return role == null ? null : new AuthorizationSnapshot(role, permissions);
        }, accountId);
    }

    /**
     * 确认当前平台会话仍有任一有效岗位，用于 Access Token 的逐请求会话验证。
     * @param accountId 令牌主体账号
     * @return 存在有效岗位且账号仍可用时为 true
     */
    public boolean hasActiveAuthorization(long accountId) {
        return authorizationForAccount(accountId) != null;
    }

    /**
     * 检查当前身份的权限必须来自实时平台授权且处于独立的平台上下文。
     * @param permission 完整平台权限码
     * @throws BusinessException 身份上下文不匹配或岗位已到期/撤销
     */
    public void requirePermission(String permission) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        AuthorizationSnapshot snapshot = authorizationForAccount(identity.actorId());
        boolean allowed = "PLATFORM".equals(identity.contextType()) && identity.sessionId() != null
                && identity.permissions().contains(permission) && snapshot != null
                && snapshot.permissions().contains(permission)
                && snapshot.roleCode().equals(identity.actorRole());
        if (!allowed) {
            audit.record("platform:authorization:denied", "permission", permission, "DENIED",
                    new AuditRecorder.AuditDetails(null, null, null, null, null,
                            identity.actorRole(), "PLATFORM_AUTHORIZATION_REQUIRED", null));
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 建立或改变平台岗位授权；监察岗位相关变更需另一位授权管理员批准后生效。
     * @param targetAccountId 被授权账号
     * @param action GRANT、RENEW、REVOKE 或 REPLACE
     * @param roleCode 目标岗位代码
     * @param expiresAt 非撤销操作的明确到期时间
     * @param reason 变更理由
     * @return 变更请求标识和待审批/已生效状态
     * @throws BusinessException 当前用户不是平台授权管理员或变更不符合职责分离规则
     */
    @Transactional
    public ChangeResult requestChange(long targetAccountId, String action, String roleCode,
                                      Instant expiresAt, String reason) {
        requirePermission("platform:authorization:manage");
        ExecutionContext.Identity actor = ExecutionContext.require();
        PlatformAuthorizationRole targetRole = PlatformAuthorizationRole.fromCode(roleCode);
        ChangeAction changeAction = ChangeAction.fromCode(action);
        validateChange(targetAccountId, changeAction, targetRole, expiresAt, reason, actor.actorId());
        lockActiveAccount(targetAccountId);
        RoleGrant current = activeGrantForUpdate(targetAccountId);
        boolean dualApproval = (current != null && current.role() == PlatformAuthorizationRole.INDEPENDENT_AUDITOR)
                || targetRole == PlatformAuthorizationRole.INDEPENDENT_AUDITOR;
        validateCurrentRole(changeAction, targetRole, expiresAt, current);
        if (targetRole.requiresNoOrganizationMembership() && hasActiveMembership(targetAccountId)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        long requestId = insertChangeRequest(targetAccountId, changeAction, targetRole,
                expiresAt, reason, actor.actorId());
        if (dualApproval) {
            appendEvent(requestId, null, "CHANGE_REQUESTED", targetAccountId, targetRole,
                    actor.actorId(), null, "PENDING", reason, expiresAt);
            audit.record("platform:authorization:changed", "platform_authorization_request",
                    Long.toString(requestId), "SUCCESS", authorizationDetails("CHANGE_REQUESTED"));
            return new ChangeResult(requestId, "PENDING", null);
        }
        Long grantId = applyChange(requestId, targetAccountId, changeAction, targetRole,
                expiresAt, reason, actor.actorId(), null, current);
        completeChangeRequest(requestId, null, "APPROVED");
        long eventSubjectId = grantId == null ? requestId : grantId;
        String resourceType = grantId == null ? "platform_authorization_request" : "platform_authorization_grant";
        audit.record("platform:authorization:changed", resourceType,
                Long.toString(eventSubjectId), "SUCCESS", authorizationDetails(changeAction.name()));
        return new ChangeResult(requestId, "APPROVED", grantId);
    }

    /**
     * 批准需双人确认的监察员授权变更，要求申请人与当前批准者均为不同的有效授权管理员。
     * @param requestId 待审批的监察岗位授权变更
     * @return 已批准的岗位授权记录编号；撤销时为空
     * @throws BusinessException 请求已处理、申请人权限失效、目标冲突或批准人无权
     */
    @Transactional
    public ChangeResult approveChange(long requestId) {
        requirePermission("platform:authorization:manage");
        ExecutionContext.Identity approver = ExecutionContext.require();
        long targetAccountId = requestTargetAccountId(requestId);
        lockActiveAccount(targetAccountId);
        ChangeRequest request = loadChangeRequestForUpdate(requestId);
        if (!"PENDING".equals(request.status()) || request.requestedBy() == approver.actorId()
                || request.targetAccountId() == approver.actorId()
                || !isCurrentAuthorizationAdmin(request.requestedBy())) {
            audit.record("platform:authorization:approval:denied", "platform_authorization_request",
                    Long.toString(requestId), "DENIED", authorizationDetails("DUAL_APPROVAL_REQUIRED"));
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        RoleGrant current = activeGrantForUpdate(request.targetAccountId());
        ChangeAction action = ChangeAction.fromCode(request.action());
        PlatformAuthorizationRole role = PlatformAuthorizationRole.fromCode(request.role());
        validateCurrentRole(action, role, request.expiresAt(), current);
        if (role.requiresNoOrganizationMembership() && hasActiveMembership(request.targetAccountId())) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        Long grantId = applyChange(requestId, request.targetAccountId(), action, role,
                request.expiresAt(), request.reason(), request.requestedBy(), approver.actorId(), current);
        completeChangeRequest(requestId, approver.actorId(), "APPROVED");
        audit.record("platform:authorization:changed", "platform_authorization_grant",
                Long.toString(grantId == null ? requestId : grantId), "SUCCESS",
                authorizationDetails("DUAL_" + action.name()));
        return new ChangeResult(requestId, "APPROVED", grantId);
    }

    /**
     * 拒绝待审批岗位变更并保留理由，不能由申请人或目标账号自行拒绝。
     * @param requestId 待审批的授权变更
     * @param decisionReason 拒绝原因
     * @return 请求编号
     * @throws BusinessException 当前用户无权或请求不再待审批
     */
    @Transactional
    public long rejectChange(long requestId, String decisionReason) {
        requirePermission("platform:authorization:manage");
        ExecutionContext.Identity approver = ExecutionContext.require();
        String safeReason = validateReason(decisionReason);
        long targetAccountId = requestTargetAccountId(requestId);
        lockActiveAccount(targetAccountId);
        ChangeRequest request = loadChangeRequestForUpdate(requestId);
        if (!"PENDING".equals(request.status()) || request.requestedBy() == approver.actorId()
                || request.targetAccountId() == approver.actorId()
                || !isCurrentAuthorizationAdmin(request.requestedBy())) {
            audit.record("platform:authorization:approval:denied", "platform_authorization_request",
                    Long.toString(requestId), "DENIED", authorizationDetails("DUAL_APPROVAL_REQUIRED"));
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        completeChangeRequest(requestId, approver.actorId(), "REJECTED", safeReason);
        appendEvent(requestId, null, "CHANGE_REJECTED", request.targetAccountId(),
                PlatformAuthorizationRole.fromCode(request.role()), request.requestedBy(), approver.actorId(),
                "DENIED", safeReason, request.expiresAt());
        audit.record("platform:authorization:changed", "platform_authorization_request",
                Long.toString(requestId), "SUCCESS", authorizationDetails("CHANGE_REJECTED"));
        return requestId;
    }

    /**
     * 列出授权管理员可处理的待确认岗位变更，不暴露与审批无关的账号资料。
     * @return 按申请时间排序的最多 100 条待处理摘要
     * @throws BusinessException 当前上下文不是有效的平台授权管理员
     */
    public List<PendingChange> pendingChanges() {
        requirePermission("platform:authorization:manage");
        return List.copyOf(jdbc.query("""
                SELECT id, action_code, target_account_id, role_code, requested_expires_at, reason,
                       requested_by, requested_at
                FROM platform_authorization_change_request
                WHERE status = 'PENDING'
                ORDER BY requested_at, id
                LIMIT 100
                """, (result, row) -> new PendingChange(result.getLong("id"), result.getString("action_code"),
                result.getLong("target_account_id"), result.getString("role_code"),
                result.getTimestamp("requested_expires_at") == null ? null
                        : result.getTimestamp("requested_expires_at").toInstant(), result.getString("reason"),
                result.getLong("requested_by"), result.getTimestamp("requested_at").toInstant())));
    }

    /**
     * 校验平台授权命令的目标、理由及明确有效期。
     * @param targetAccountId 目标账号编号
     * @param expiresAt 明确到期时间
     * @param reason 操作理由
     */
    private void validateTargetAndExpiry(long targetAccountId, Instant expiresAt, String reason) {
        if (targetAccountId <= 0 || expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        validateReason(reason);
    }

    /**
     * 校验部署操作者编号，不从环境变量或运行账号推断其真实性。
     * @param operatorId 部署操作者外部编号
     */
    private void validateOperator(long operatorId) {
        if (operatorId <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /**
     * 校验授权动作、目标账号和授权岗位的组合。
     * @param targetAccountId 被授权账号
     * @param action 操作类型
     * @param role 目标岗位
     * @param expiresAt 到期时间
     * @param reason 操作理由
     * @param actorId 当前操作者
     */
    private void validateChange(long targetAccountId, ChangeAction action, PlatformAuthorizationRole role,
                                Instant expiresAt, String reason, long actorId) {
        if (targetAccountId <= 0 || targetAccountId == actorId || role == null || action == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (action == ChangeAction.REVOKE) {
            if (expiresAt != null) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
        } else if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        validateReason(reason);
    }

    /**
     * 限制授权记录保存的理由格式与长度。
     * @param reason 用户提供的操作理由
     * @return 去除首尾空白的理由
     */
    private String validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH
                || !SAFE_TEXT.matcher(reason).matches()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return reason.trim();
    }

    /**
     * 要求目标账号存在且未锁定或禁用。
     * @param accountId 全局账号编号
     */
    private void requireActiveAccount(long accountId) {
        String status = jdbc.query("SELECT status FROM auth_account WHERE id = ?",
                result -> result.next() ? result.getString(1) : null, accountId);
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 按统一锁顺序锁定并验证目标账号，串行化该账号的授权请求及审批。
     * @param accountId 全局账号编号
     * @throws BusinessException 账号不存在、锁定或已禁用
     */
    private void lockActiveAccount(long accountId) {
        String status = jdbc.query("SELECT status FROM auth_account WHERE id = ? FOR UPDATE",
                result -> result.next() ? result.getString(1) : null, accountId);
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 检查账号是否有 ACTIVE 的组织成员关系。
     * @param accountId 全局账号编号
     * @return 存在成员关系时为 true
     */
    private boolean hasActiveMembership(long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM business_membership WHERE actor_id = ? AND status = 'ACTIVE')
                """, Boolean.class, accountId));
    }

    /**
     * 锁定并读取目标账号的当前未撤销岗位记录。
     * @param accountId 全局账号编号
     * @return 当前授权，账号尚无授权时为空
     */
    private RoleGrant activeGrantForUpdate(long accountId) {
        return jdbc.query("""
                SELECT id, role_code, expires_at FROM platform_authorization_grant
                WHERE account_id = ? AND revoked_at IS NULL FOR UPDATE
                """, result -> result.next() ? new RoleGrant(result.getLong("id"),
                PlatformAuthorizationRole.fromCode(result.getString("role_code")),
                result.getTimestamp("expires_at").toInstant()) : null, accountId);
    }

    /**
     * 验证操作与目标账号当前岗位状态匹配，避免重复授予或降权覆盖。
     * @param action 请求动作
     * @param role 目标岗位
     * @param expiresAt 请求到期时间
     * @param current 当前岗位授权
     */
    private void validateCurrentRole(ChangeAction action, PlatformAuthorizationRole role,
                                     Instant expiresAt, RoleGrant current) {
        boolean currentActive = current != null && current.expiresAt().isAfter(clock.instant());
        switch (action) {
            case GRANT -> {
                if (currentActive) throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            case RENEW -> {
                if (!currentActive || current.role() != role || !expiresAt.isAfter(current.expiresAt())) {
                    throw new BusinessException(PlatformError.DATA_CONFLICT);
                }
            }
            case REVOKE -> {
                if (!currentActive || current.role() != role) throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            case REPLACE -> {
                if (!currentActive || current.role() == role) throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
        }
    }

    /**
     * 创建授权申请并在单人权限下立即进入终态，监察岗位保留待双人审批状态。
     * @param targetAccountId 被授权账号
     * @param action 授权变更动作
     * @param role 目标岗位
     * @param expiresAt 明确到期时间
     * @param reason 授权理由
     * @param actorId 当前授权管理员
     * @param pending 是否需要第二位授权人
     * @return 授权变更请求编号
     */
    private long insertChangeRequest(long targetAccountId, ChangeAction action, PlatformAuthorizationRole role,
                                     Instant expiresAt, String reason, long actorId) {
        Long requestId = jdbc.queryForObject("""
                INSERT INTO platform_authorization_change_request
                    (action_code, target_account_id, role_code, requested_by, requested_expires_at, reason,
                     status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, Long.class, action.name(), targetAccountId, role.code(), actorId,
                expiresAt == null ? null : Timestamp.from(expiresAt), reason.trim());
        if (requestId == null) throw new IllegalStateException("Authorization request was not created");
        return requestId;
    }

    /**
     * 应用一项授权变更，历史授权只允许单向撤销，续期始终新建授权版本。
     * @param requestId 授权变更请求
     * @param accountId 目标账号
     * @param action 操作类型
     * @param role 目标岗位
     * @param expiresAt 新授权的明确到期时间
     * @param reason 操作理由
     * @param actorId 变更申请人
     * @param approverId 第二位审批人，可为空
     * @param current 当前授权记录
     * @return 新授权编号；撤销时为空
     */
    private Long applyChange(long requestId, long accountId, ChangeAction action, PlatformAuthorizationRole role,
                             Instant expiresAt, String reason, long actorId, Long approverId, RoleGrant current) {
        Long grantId = null;
        if (current != null) {
            jdbc.update("UPDATE platform_authorization_grant SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                    Timestamp.from(clock.instant()), current.id());
        }
        if (action != ChangeAction.REVOKE) {
            grantId = insertGrant(accountId, role, actorId, expiresAt);
        }
        appendEvent(requestId, grantId, action.name(), accountId, role,
                actorId, approverId, "SUCCESS", reason, expiresAt);
        return grantId;
    }

    /**
     * 新增岗位授权版本。
     * @param accountId 目标账号
     * @param role 授予岗位
     * @param actorId 授权管理员，可为空用于离线引导
     * @param expiresAt 明确的岗位到期时间
     * @return 数据库授权编号
     */
    private long insertGrant(long accountId, PlatformAuthorizationRole role, Long actorId, Instant expiresAt) {
        Long grantId = jdbc.queryForObject("""
                INSERT INTO platform_authorization_grant(account_id, role_code, granted_by, expires_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, accountId, role.code(), actorId, Timestamp.from(expiresAt));
        if (grantId == null) throw new IllegalStateException("Platform authorization grant was not created");
        return grantId;
    }

    /**
     * 向不可变授权历史追加一个事件。
     * @param requestId 授权申请编号
     * @param grantId 生效岗位编号，可为空
     * @param action 事件类型
     * @param targetAccountId 目标账号
     * @param role 受影响岗位
     * @param actorId 发起人
     * @param approverId 独立审批人
     * @param outcome SUCCESS、PENDING 或 DENIED
     * @param reason 事件理由
     * @param expiresAt 变更后的到期时间
     */
    private void appendEvent(Long requestId, Long grantId, String action, long targetAccountId,
                             PlatformAuthorizationRole role, Long actorId, Long approverId,
                             String outcome, String reason, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO platform_authorization_event
                    (request_id, grant_id, action_code, target_account_id, role_code, actor_account_id,
                     approver_account_id, outcome, reason, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, grantId, action, targetAccountId, role.code(), actorId, approverId,
                outcome, reason.trim(), expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    /**
     * 完成一条待审批申请并保留独立批准人。
     * @param requestId 申请编号
     * @param approverId 审批人；单人授权直接生效时为空
     * @param status APPROVED 或 REJECTED
     */
    private void completeChangeRequest(long requestId, Long approverId, String status) {
        completeChangeRequest(requestId, approverId, status, null);
    }

    /**
     * 完成授权申请并记录决定时间与可选拒绝理由。
     * @param requestId 申请编号
     * @param approverId 审批人
     * @param status APPROVED 或 REJECTED
     * @param decisionReason 拒绝理由，可为空
     */
    private void completeChangeRequest(long requestId, Long approverId, String status, String decisionReason) {
        int updated = jdbc.update("""
                UPDATE platform_authorization_change_request
                SET status = ?, approved_by = ?, decision_reason = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, status, approverId, decisionReason, requestId);
        if (updated != 1) throw new BusinessException(PlatformError.DATA_CONFLICT);
    }

    /**
     * 锁定读取待审批记录。
     * @param requestId 授权变更请求编号
     * @return 持久化请求快照
     */
    private ChangeRequest loadChangeRequestForUpdate(long requestId) {
        if (requestId <= 0) throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        ChangeRequest request = jdbc.query("""
                SELECT id, action_code, target_account_id, role_code, requested_by, requested_expires_at,
                       reason, status
                FROM platform_authorization_change_request WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new ChangeRequest(result.getLong("id"),
                result.getString("action_code"), result.getLong("target_account_id"), result.getString("role_code"),
                result.getLong("requested_by"), result.getTimestamp("requested_expires_at") == null ? null
                : result.getTimestamp("requested_expires_at").toInstant(), result.getString("reason"),
                result.getString("status")) : null, requestId);
        if (request == null) throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        return request;
    }

    /**
     * 先无锁读取授权请求目标，使审批路径按账号行、请求行的固定顺序加锁。
     * @param requestId 授权请求编号
     * @return 请求目标账号编号
     * @throws BusinessException 请求编号无效或不存在
     */
    private long requestTargetAccountId(long requestId) {
        if (requestId <= 0) throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        Long target = jdbc.query("SELECT target_account_id FROM platform_authorization_change_request WHERE id = ?",
                result -> result.next() ? result.getLong(1) : null, requestId);
        if (target == null) throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        return target;
    }

    /**
     * 判断账号当前是否持有有效的平台授权管理员岗位。
     * @param accountId 授权管理员账号
     * @return 仍有效时为 true
     */
    private boolean isCurrentAuthorizationAdmin(long accountId) {
        AuthorizationSnapshot snapshot = authorizationForAccount(accountId);
        return snapshot != null && PlatformAuthorizationRole.AUTHORIZATION_ADMIN.code().equals(snapshot.roleCode());
    }

    /**
     * 构造带稳定岗位变更原因码的审计详情。
     * @param reasonCode 稳定操作码
     * @return 不含授权理由正文的审计详情
     */
    private AuditRecorder.AuditDetails authorizationDetails(String reasonCode) {
        return new AuditRecorder.AuditDetails(null, null, null, null, null,
                ExecutionContext.require().actorRole(), reasonCode, null);
    }

    /**
     * 记录未成功的离线引导尝试，失败事务回滚后仍独立追加审计事件。
     * @param operatorId 部署操作者编号
     * @param targetAccountId 目标账号
     * @param reasonCode 稳定失败原因码
     */
    private void recordBootstrapFailure(long operatorId, long targetAccountId, String reasonCode) {
        audit.recordServiceEvent(operatorId, "platform:authorization:bootstrap", "platform_authorization_target",
                Long.toString(targetAccountId), "DENIED", reasonCode);
    }

    /**
     * 返回授权申请的摘要，不包含岗位理由或目标账号展示资料。
     * @param id 授权请求编号
     * @param status 当前状态
     * @param grantId 生效授权编号，可为空
     */
    public record ChangeResult(long id, String status, Long grantId) {
        /**
         * @param id 授权变更请求编号
         * @param status PENDING 或 APPROVED
         * @param grantId 成功变更后新授权编号；撤销或待审批时为空
         */
    }

    /** 当前需要第二位授权管理员处理的变更申请摘要。 */
    public record PendingChange(long id, String action, long targetAccountId, String roleCode, Instant expiresAt,
                                String reason, long requestedBy, Instant requestedAt) {
        /**
         * @param id 授权变更请求编号
         * @param action 申请动作
         * @param targetAccountId 被授权账号编号
         * @param roleCode 当前申请涉及的岗位
         * @param expiresAt 申请到期时间；撤销时为空
         * @param reason 授权管理员填写的审查理由
         * @param requestedBy 申请授权管理员账号编号
         * @param requestedAt 服务端记录的申请时间
         */
    }

    /**
     * 当前账号实时授权的岗位和权限快照。
     * @param roleCode 平台岗位代码
     * @param permissions 数据库岗位权限集合
     */
    public record AuthorizationSnapshot(String roleCode, Set<String> permissions) {
        /** 防止调用方修改共享权限集合。 */
        public AuthorizationSnapshot {
            permissions = Set.copyOf(permissions);
        }
    }

    /** 当前授权行的锁定快照。 */
    private record RoleGrant(long id, PlatformAuthorizationRole role, Instant expiresAt) {
        /**
         * @param id 授权记录编号
         * @param role 当前岗位
         * @param expiresAt 当前授权的失效时间
         */
    }

    /** 已持久化的岗位变更申请。 */
    private record ChangeRequest(long id, String action, long targetAccountId, String role,
                                 long requestedBy, Instant expiresAt, String reason, String status) {
        /**
         * @param id 授权变更请求编号
         * @param action 请求动作代码
         * @param targetAccountId 被变更账号编号
         * @param role 涉及的平台岗位代码
         * @param requestedBy 发起申请的授权管理员账号
         * @param expiresAt 申请到期时间；撤销时为空
         * @param reason 申请理由
         * @param status 当前待处理或终态
         */
    }

    /**
     * 允许的授权变更代码。
     */
    private enum ChangeAction {
        /** 创建首次岗位授权。 */
        GRANT,
        /** 延长原岗位到期时间并生成新授权版本。 */
        RENEW,
        /** 撤销当前岗位。 */
        REVOKE,
        /** 原子撤销现岗位并授予另一个岗位。 */
        REPLACE;

        /**
         * 将请求代码解析为允许的授权动作。
         * @param code 客户端动作代码
         * @return 允许的变更动作
         */
        static ChangeAction fromCode(String code) {
            try {
                return ChangeAction.valueOf(code);
            } catch (RuntimeException exception) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
        }
    }
}
