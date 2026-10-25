package com.company.labor.modules.identity;

import com.company.labor.modules.identity.OrganizationDtos.CreateRequest;
import com.company.labor.modules.identity.OrganizationDtos.JoinRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipDecisionRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipRequestDecision;
import com.company.labor.modules.identity.OrganizationDtos.MembershipView;
import com.company.labor.modules.identity.OrganizationDtos.MembershipRequestView;
import com.company.labor.modules.identity.OrganizationDtos.OrganizationReviewDecision;
import com.company.labor.modules.identity.OrganizationDtos.ReviewRequest;
import com.company.labor.modules.identity.OrganizationDtos.UpdateRequest;
import com.company.labor.modules.identity.OrganizationDtos.View;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 实现首批组织创建、成员加入和平台审核流程，身份签发仍由外部适配器负责。 */
@Service
public class OrganizationService {
    /** 组织与成员持久化入口。 */
    private final JdbcTemplate jdbc;
    /** 生成业务标识的共享序列服务。 */
    private final SequenceIds ids;
    /** 标识化审计入口。 */
    private final AuditRecorder audit;
    /** 组织成员权限查询服务。 */
    private final OrganizationAuthorization authorization;

    /** 创建组织业务服务。 */
    public OrganizationService(JdbcTemplate jdbc, SequenceIds ids, AuditRecorder audit,
                               OrganizationAuthorization authorization) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.audit = audit;
        this.authorization = authorization;
    }

    /**
     * 创建组织草稿并将当前身份登记为初始管理员。
     * @param request 主体和授权引用
     * @return 新建组织
     * @throws BusinessException 主体重复或当前身份未认证
     */
    @Transactional
    public View create(CreateRequest request) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        ExecutionContext.checkPermission("organization:create");
        if (identity.tenantId() != null) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        String legalName = required(request.legalName(), "legalName");
        String registrationId = required(request.registrationId(), "registrationId");
        String authorizationReference = required(request.authorizationReference(), "authorizationReference");
        long organizationId = ids.nextId();
        int inserted = jdbc.update("""
                INSERT INTO business_organization
                    (id, tenant_id, organization_role, legal_name, registration_id, authorization_reference,
                     qualification_reference, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                ON CONFLICT (registration_id) DO NOTHING
                """, organizationId, organizationId, request.organizationRole().name(), legalName, registrationId,
                authorizationReference, optional(request.qualificationReference()), identity.actorId());
        if (inserted != 1) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        insertRevision(organizationId, 1, legalName, registrationId, authorizationReference,
                optional(request.qualificationReference()), identity.actorId());
        jdbc.update("""
                INSERT INTO business_membership(organization_id, tenant_id, actor_id, member_role, status, created_by)
                VALUES (?, ?, ?, 'ORGANIZATION_ADMIN', 'ACTIVE', ?)
                """, organizationId, organizationId, identity.actorId(), identity.actorId());
        audit.record("organization:create", "organization", Long.toString(organizationId), "SUCCESS");
        return get(organizationId);
    }

    /**
     * 提交组织申请，只有组织管理员可以操作。
     * @param organizationId 组织标识
     * @return 提交后的组织
     * @throws BusinessException 组织状态、材料或版本不允许提交
     */
    @Transactional
    public View submit(long organizationId) {
        ExecutionContext.checkPermission("organization:organizations:write");
        authorization.membership(organizationId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
        OrganizationRow organization = requireRow(organizationId);
        if (organization.role() == OrganizationRole.LABOR_SUPPLIER
                && organization.qualificationReference() == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (organization.status() != OrganizationStatus.DRAFT
                && organization.status() != OrganizationStatus.NEEDS_CORRECTION) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        int updated = jdbc.update("""
                UPDATE business_organization
                SET status = 'PENDING_REVIEW', version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ?
                  AND (status = 'DRAFT' OR (status = 'NEEDS_CORRECTION'
                      AND application_version > COALESCE(reviewed_application_version, 0)))
                """, organizationId, organizationId, organization.version());
        ensureUpdated(updated);
        audit.record("organization:submit", "organization", Long.toString(organizationId), "SUCCESS");
        return get(organizationId);
    }

    /**
     * 更新尚未批准的组织资料并推进乐观版本。
     * @param organizationId 组织标识
     * @param request 修正后的主体资料和客户端版本
     * @return 更新后的组织视图
     * @throws BusinessException 当前状态、成员权限或版本不允许修改
     */
    @Transactional
    public View update(long organizationId, UpdateRequest request) {
        ExecutionContext.checkPermission("organization:organizations:write");
        authorization.membership(organizationId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
        OrganizationRow organization = requireRow(organizationId);
        if (organization.status() != OrganizationStatus.DRAFT
                && organization.status() != OrganizationStatus.NEEDS_CORRECTION) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (organization.version() != request.expectedVersion()) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        String legalName = required(request.legalName(), "legalName");
        String registrationId = required(request.registrationId(), "registrationId");
        String authorizationReference = required(request.authorizationReference(), "authorizationReference");
        if (organization.role() == OrganizationRole.LABOR_SUPPLIER && optional(request.qualificationReference()) == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_organization
                WHERE registration_id = ? AND id <> ?
                """, Integer.class, registrationId, organizationId) > 0) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        int updated = jdbc.update("""
                UPDATE business_organization
                SET legal_name = ?, registration_id = ?, authorization_reference = ?,
                    qualification_reference = ?, version = version + 1,
                    application_version = application_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ? AND status IN ('DRAFT', 'NEEDS_CORRECTION')
                """, legalName, registrationId, authorizationReference,
                optional(request.qualificationReference()), organizationId, organizationId, request.expectedVersion());
        ensureUpdated(updated);
        insertRevision(organizationId, organization.applicationVersion() + 1, legalName, registrationId,
                authorizationReference, optional(request.qualificationReference()), ExecutionContext.require().actorId());
        audit.record("organization:update", "organization", Long.toString(organizationId), "SUCCESS");
        return get(organizationId);
    }

    /**
     * 执行平台组织审核；原创建人不能独立审核自己的申请。
     * @param organizationId 组织标识
     * @param request 审核决定和理由
     * @return 审核后的组织
     * @throws BusinessException 缺少平台权限、状态不正确或存在利益冲突
     */
    @Transactional
    public View review(long organizationId, ReviewRequest request) {
        authorization.requirePlatformPermission("platform:organizations:review");
        ExecutionContext.Identity identity = ExecutionContext.require();
        OrganizationRow organization = requireRow(organizationId);
        if (organization.createdBy() == identity.actorId()
                || organization.status() != OrganizationStatus.PENDING_REVIEW) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        String reason = optional(request.reason());
        if (request.decision() != OrganizationReviewDecision.APPROVE && reason == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (reason != null && reason.length() > 1000) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (request.decision() == OrganizationReviewDecision.APPROVE
                && organization.role() == OrganizationRole.LABOR_SUPPLIER
                && organization.qualificationReference() == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        OrganizationStatus next = switch (request.decision()) {
            case APPROVE -> OrganizationStatus.APPROVED;
            case NEEDS_CORRECTION -> OrganizationStatus.NEEDS_CORRECTION;
            case REJECT -> OrganizationStatus.REJECTED;
        };
        int updated = jdbc.update("""
                UPDATE business_organization
                SET status = ?, reviewed_by = ?, review_reason = ?,
                    reviewed_application_version = application_version, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ? AND status = 'PENDING_REVIEW'
                """, next.name(), identity.actorId(), reason, organizationId, organizationId, organization.version());
        ensureUpdated(updated);
        jdbc.update("""
                INSERT INTO business_review_decision
                    (tenant_id, resource_type, resource_id, resource_version, from_status, decision,
                     to_status, reviewer_id, reason)
                VALUES (?, 'ORGANIZATION', ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, organizationId, organization.applicationVersion(), organization.status().name(),
                request.decision().name(), next.name(), identity.actorId(), reason);
        audit.record("organization:review", "organization", Long.toString(organizationId), "SUCCESS");
        return get(organizationId);
    }

    /**
     * 提交加入已通过组织的请求，创建人不能绕过管理员审批。
     * @param organizationId 目标组织
     * @param request 请求的成员角色
     * @return 加入请求标识
     * @throws BusinessException 组织未通过、已有成员或重复申请
     */
    @Transactional
    public long join(long organizationId, JoinRequest request) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        ExecutionContext.checkPermission("organization:membership:request");
        if (identity.tenantId() != null && identity.tenantId() != organizationId) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        if (request.requestedRole() == MembershipRole.ORGANIZATION_ADMIN) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        authorization.requireApproved(organizationId);
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_membership
                WHERE organization_id = ? AND tenant_id = ? AND actor_id = ? AND status = 'ACTIVE'
                """, Integer.class, organizationId, organizationId, identity.actorId()) > 0) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        long requestId = ids.nextId();
        int inserted = jdbc.update("""
                INSERT INTO business_membership_request
                    (id, organization_id, tenant_id, actor_id, requested_role, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (tenant_id, organization_id, actor_id) WHERE status = 'PENDING' DO NOTHING
                """, requestId, organizationId, organizationId, identity.actorId(), request.requestedRole().name());
        if (inserted != 1) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        audit.record("membership:request", "organization", Long.toString(organizationId), "SUCCESS");
        return requestId;
    }

    /**
     * 查询组织管理员可处理的待加入请求。
     * @param organizationId 目标组织
     * @return 按创建时间排序的待处理请求
     * @throws BusinessException 当前身份不是有效组织管理员
     */
    public java.util.List<MembershipRequestView> pendingMembershipRequests(long organizationId) {
        ExecutionContext.checkPermission("organization:members:manage");
        authorization.membership(organizationId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
        return jdbc.query("""
                SELECT id, actor_id, requested_role, created_at
                FROM business_membership_request
                WHERE tenant_id = ? AND organization_id = ? AND status = 'PENDING'
                ORDER BY created_at, id
                LIMIT 100
                """, (result, row) -> new MembershipRequestView(result.getLong("id"),
                result.getLong("actor_id"), MembershipRole.valueOf(result.getString("requested_role")),
                result.getTimestamp("created_at").toInstant()), organizationId, organizationId);
    }

    /**
     * 查询平台待审核组织申请。
     * @return 按提交时间排序的申请摘要
     * @throws BusinessException 当前身份没有平台审核权限
     */
    public java.util.List<View> pendingReviews() {
        authorization.requirePlatformPermission("platform:organizations:review");
        return jdbc.query("""
                SELECT id, organization_role, legal_name, registration_id, qualification_reference,
                       status, version, application_version, created_by, reviewed_by, review_reason
                FROM business_organization
                WHERE status = 'PENDING_REVIEW' AND tenant_id = id
                ORDER BY updated_at, id
                LIMIT 100
                """, (result, row) -> new OrganizationRow(result.getLong("id"),
                OrganizationRole.valueOf(result.getString("organization_role")), result.getString("legal_name"),
                result.getString("registration_id"), result.getString("qualification_reference"),
                OrganizationStatus.valueOf(result.getString("status")), result.getLong("version"),
                result.getLong("application_version"), result.getLong("created_by"),
                result.getObject("reviewed_by", Long.class), result.getString("review_reason")).view());
    }

    /**
     * 由组织管理员批准或拒绝加入请求。
     * @param organizationId 目标组织
     * @param requestId 加入请求标识
     * @param request 管理员决定
     * @return 处理后的成员视图，拒绝时返回 null
     * @throws BusinessException 当前身份不是管理员、请求过期或版本冲突
     */
    @Transactional
    public MembershipView decideJoin(long organizationId, long requestId, MembershipDecisionRequest request) {
        ExecutionContext.checkPermission("organization:members:manage");
        authorization.membership(organizationId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
        authorization.requireApproved(organizationId);
        ExecutionContext.Identity identity = ExecutionContext.require();
        MembershipRequestRow pending = jdbc.query("""
                SELECT actor_id, requested_role, status, version
                FROM business_membership_request
                WHERE id = ? AND organization_id = ? AND tenant_id = ?
                """, result -> result.next() ? new MembershipRequestRow(result.getLong("actor_id"),
                MembershipRole.valueOf(result.getString("requested_role")),
                MembershipRequestStatus.valueOf(result.getString("status")), result.getLong("version")) : null,
                requestId, organizationId, organizationId);
        if (pending == null || pending.status() != MembershipRequestStatus.PENDING
                || pending.actorId() == identity.actorId()) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        String reason = optional(request.reason());
        if (request.decision() == MembershipRequestDecision.REJECT && reason == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        int updated = jdbc.update("""
                UPDATE business_membership_request
                SET status = ?, reviewed_by = ?, review_reason = ?, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND version = ?
                """, request.decision() == MembershipRequestDecision.APPROVE ? "APPROVED" : "REJECTED",
                identity.actorId(), reason, requestId, organizationId, pending.version());
        ensureUpdated(updated);
        if (request.decision() == MembershipRequestDecision.REJECT) {
            audit.record("membership:reject", "organization", Long.toString(organizationId), "SUCCESS");
            return null;
        }
        jdbc.update("""
                INSERT INTO business_membership(organization_id, tenant_id, actor_id, member_role, status, created_by)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (organization_id, actor_id) DO UPDATE
                SET member_role = EXCLUDED.member_role, status = 'ACTIVE',
                    version = business_membership.version + 1, updated_at = CURRENT_TIMESTAMP
                """, organizationId, organizationId, pending.actorId(), pending.requestedRole().name(), identity.actorId());
        audit.record("membership:approve", "organization", Long.toString(organizationId), "SUCCESS");
        return new MembershipView(organizationId, pending.actorId(), pending.requestedRole(), MembershipStatus.ACTIVE);
    }

    /**
     * 读取组织状态；成员或平台审核员可以查看，其他身份得到统一拒绝。
     * @param organizationId 组织标识
     * @return 组织视图
     * @throws BusinessException 组织不存在或无权读取
     */
    public View get(long organizationId) {
        OrganizationRow row = requireRow(organizationId);
        if (!isMember(organizationId) && !hasPermission("platform:organizations:review")) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return row.view();
    }

    /** 判断当前身份是否是该组织的有效成员。 */
    private boolean isMember(long organizationId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() != null && identity.tenantId() != organizationId) {
            return false;
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_membership
                WHERE organization_id = ? AND tenant_id = ? AND actor_id = ? AND status = 'ACTIVE'
                """, Integer.class, organizationId, organizationId, identity.actorId()) > 0;
    }

    /** 判断已验证令牌是否含指定平台权限。 */
    private boolean hasPermission(String permission) {
        return ExecutionContext.require().permissions().contains(permission);
    }

    /** 查询组织，不把不存在的组织与可访问性差异暴露给调用方。 */
    private OrganizationRow requireRow(long organizationId) {
        OrganizationRow row = jdbc.query("""
                SELECT id, organization_role, legal_name, registration_id, qualification_reference,
                       status, version, application_version, created_by, reviewed_by, review_reason
                FROM business_organization WHERE id = ? AND tenant_id = id
                """, result -> result.next() ? new OrganizationRow(result.getLong("id"),
                OrganizationRole.valueOf(result.getString("organization_role")),
                result.getString("legal_name"), result.getString("registration_id"),
                result.getString("qualification_reference"), OrganizationStatus.valueOf(result.getString("status")),
                result.getLong("version"), result.getLong("application_version"), result.getLong("created_by"),
                result.getObject("reviewed_by", Long.class), result.getString("review_reason")) : null, organizationId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 保存只追加的组织资料版本，供审核决定引用并还原其申请依据。 */
    private void insertRevision(long organizationId, long revision, String legalName, String registrationId,
                                String authorizationReference, String qualificationReference, long actorId) {
        jdbc.update("""
                INSERT INTO business_organization_revision
                    (organization_id, tenant_id, revision, legal_name, registration_id,
                     authorization_reference, qualification_reference, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, organizationId, revision, legalName, registrationId,
                authorizationReference, qualificationReference, actorId);
    }

    /** 把空白输入统一视为缺失，避免把无意义引用写入审核事实。 */
    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return value.trim();
    }

    /** 将可选文本标准化为空值。 */
    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 保证乐观版本更新确实影响了预期行。 */
    private void ensureUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
    }

    /** 组织查询快照。 */
    private record OrganizationRow(long id, OrganizationRole role, String legalName, String registrationId,
                                   String qualificationReference, OrganizationStatus status, long version,
                                   long applicationVersion, long createdBy, Long reviewedBy, String reviewReason) {
        /** 映射为 API 返回值。 */
        private View view() {
            return new View(id, role, legalName, registrationId, status, version, applicationVersion,
                    createdBy, reviewedBy, reviewReason);
        }
    }

    /** 加入请求查询快照。 */
    private record MembershipRequestRow(long actorId, MembershipRole requestedRole,
                                        MembershipRequestStatus status, long version) {
    }
}
