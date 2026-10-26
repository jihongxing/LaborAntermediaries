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
import com.company.labor.modules.platform.ReviewCaseHistoryService;
import com.company.labor.modules.platform.ReviewCaseType;
import com.company.labor.modules.platform.PlatformTaskService;
import com.company.labor.modules.platform.PlatformTaskType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.List;
import java.util.HashSet;

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
    /** 首次提交时创建事项并为后续操作提供版本化审计关联。 */
    private final ReviewCaseHistoryService reviewHistory;
    /** 组织提交、认领及审核决定共享事务的岗位任务服务。 */
    private final PlatformTaskService platformTasks;

    /**
     * 创建组织业务服务。
     * @param jdbc 组织与成员持久化入口
     * @param ids 业务标识生成服务
     * @param audit 追加式审计入口
     * @param authorization 组织成员权限查询服务
     * @param reviewHistory 稳定事项与历史服务
     * @param platformTasks 版本绑定的审核任务服务
     */
    public OrganizationService(JdbcTemplate jdbc, SequenceIds ids, AuditRecorder audit,
                               OrganizationAuthorization authorization, ReviewCaseHistoryService reviewHistory,
                               PlatformTaskService platformTasks) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.audit = audit;
        this.authorization = authorization;
        this.reviewHistory = reviewHistory;
        this.platformTasks = platformTasks;
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
        audit.record("organization:create", "organization", Long.toString(organizationId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.ORGANIZATION, organizationId, organizationId, 1,
                        "DRAFT_CREATED"));
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
        requireApplicationAdministrator(organizationId);
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
        long reviewCaseId = reviewHistory.openOnSubmission(ReviewCaseType.ORGANIZATION, organizationId,
                organizationId, organization.applicationVersion(), ExecutionContext.require().actorId());
        platformTasks.openReviewTaskOnSubmission(reviewCaseId, ReviewCaseType.ORGANIZATION, organizationId,
                organizationId, organization.applicationVersion());
        audit.record("organization:submit", "organization", Long.toString(organizationId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.ORGANIZATION, organizationId, organizationId,
                        organization.applicationVersion(), "APPLICATION_SUBMITTED"));
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
        requireApplicationAdministrator(organizationId);
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
        audit.record("organization:update", "organization", Long.toString(organizationId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.ORGANIZATION, organizationId, organizationId,
                        organization.applicationVersion() + 1, "APPLICATION_VERSION_CREATED"));
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
        PlatformTaskService.TaskRef taskRef = platformTasks.requireClaimedReviewTask(
                ReviewCaseType.ORGANIZATION, organizationId, organization.applicationVersion());
        String reason = optional(request.reason());
        if (request.decision() != OrganizationReviewDecision.APPROVE && reason == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        List<com.company.labor.modules.identity.OrganizationDtos.CorrectionItemRequest> correctionItems =
                request.correctionItems() == null ? List.of() : request.correctionItems();
        if (request.decision() == OrganizationReviewDecision.NEEDS_CORRECTION
                && (correctionItems.isEmpty() || correctionItems.size() > 20)) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (request.decision() != OrganizationReviewDecision.NEEDS_CORRECTION && !correctionItems.isEmpty()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        Set<String> correctionCodes = new HashSet<>();
        for (var item : correctionItems) {
            if (item == null || item.itemCode() == null || item.title() == null || item.description() == null
                    || item.applicantAction() == null) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            String code = item.itemCode().trim();
            if (code.isEmpty() || item.title().trim().isEmpty() || item.description().trim().isEmpty()
                    || item.applicantAction().trim().isEmpty() || !correctionCodes.add(code)) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
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
        if (request.decision() == OrganizationReviewDecision.NEEDS_CORRECTION) {
            appendCorrectionItems(taskRef, organizationId, correctionItems, identity.actorId());
        }
        audit.record("organization:review", "organization", Long.toString(organizationId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.ORGANIZATION, organizationId, organizationId,
                        organization.applicationVersion(), request.decision().name()));
        platformTasks.completeReviewTask(taskRef, request.decision().name());
        return requireRow(organizationId).view(false);
    }

    /** 将补正清单和审核决定置于同一事务，并绑定不可变的事项、任务及申请版本。 */
    private void appendCorrectionItems(PlatformTaskService.TaskRef taskRef,
                                       long organizationId,
                                       List<com.company.labor.modules.identity.OrganizationDtos.CorrectionItemRequest> items,
                                       long actorId) {
        reviewHistory.appendCorrectionItems(ReviewCaseType.ORGANIZATION, organizationId, organizationId, taskRef,
                items.stream().map(item -> new ReviewCaseHistoryService.CorrectionItemInput(item.itemCode(),
                        item.title(), item.description(), item.applicantAction())).toList(), actorId);
    }

    /**
     * 通过当前审核员已认领的组织审核任务读取申请详情。
     * @param taskId 已认领的组织审核任务编号
     * @return 该任务绑定版本的组织申请最小详情
     * @throws BusinessException 当前账号未持有有效认领、任务类别错误或任务版本已失效
     */
    @Transactional
    public OrganizationReviewSubject reviewSubject(long taskId) {
        PlatformTaskService.TaskDetails details = platformTasks.details(taskId);
        PlatformTaskService.TaskView task = details.task();
        if (!PlatformTaskType.ORGANIZATION_REVIEW.name().equals(task.taskType())
                || !ReviewCaseType.ORGANIZATION.name().equals(task.resourceType())) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        OrganizationRow organization = requireRow(Long.parseLong(task.resourceId()));
        if (organization.applicationVersion() != task.resourceVersion()
                || organization.status() != OrganizationStatus.PENDING_REVIEW) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        return new OrganizationReviewSubject(Long.toString(organization.id()), organization.role().name(),
                organization.legalName(), organization.registrationId(), organization.authorizationReference(),
                organization.qualificationReference(), organization.status().name(),
                organization.applicationVersion(), organization.version());
    }

    /**
     * 通过指定的已认领组织审核任务提交决定。
     * @param taskId 已认领的组织审核任务编号
     * @param request 决定、申请方可见理由和逐项补正内容
     * @return 可由组织有效成员查看的组织状态
     * @throws BusinessException 任务授权、回避、冲突或版本校验失败
     */
    @Transactional
    public View reviewTask(long taskId, ReviewRequest request) {
        OrganizationReviewSubject subject = reviewSubject(taskId);
        return review(Long.parseLong(subject.organizationId()), request);
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
     * 查询组织审核岗位最小任务队列，不返回未认领事项详情或材料引用。
     * @return 当前审核岗位的任务摘要
     * @throws BusinessException 当前身份没有平台审核权限
     */
    public List<PlatformTaskService.QueueItem> pendingReviews() {
        return platformTasks.queue(PlatformTaskType.ORGANIZATION_REVIEW);
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
        boolean member = isMember(organizationId);
        if (!member) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return row.view(isOrganizationAdministrator(organizationId));
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

    /** 组织材料引用只向该组织管理员或平台审核员披露。 */
    private boolean isOrganizationAdministrator(long organizationId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_membership
                WHERE organization_id = ? AND tenant_id = ? AND actor_id = ?
                  AND member_role = 'ORGANIZATION_ADMIN' AND status = 'ACTIVE'
                """, Integer.class, organizationId, organizationId, ExecutionContext.require().actorId()) > 0;
    }

    /**
     * 允许账号本人处理自己组织的审核申请，同时要求已进入组织上下文的更新符合岗位权限。
     * @param organizationId 目标组织
     * @throws BusinessException 申请人不匹配、管理员关系撤销或当前身份缺少授权
     */
    private void requireApplicationAdministrator(long organizationId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() != null) {
            ExecutionContext.checkPermission("organization:organizations:write");
            authorization.membership(organizationId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
            return;
        }
        boolean applicant = identity.permissions().contains("organization:create")
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM business_organization o
                    JOIN business_membership m ON m.organization_id = o.id AND m.tenant_id = o.tenant_id
                    WHERE o.id = ? AND o.tenant_id = o.id AND o.created_by = ?
                      AND o.status IN ('DRAFT', 'NEEDS_CORRECTION')
                      AND m.actor_id = ? AND m.member_role = 'ORGANIZATION_ADMIN' AND m.status = 'ACTIVE'
                )
                """, Boolean.class, organizationId, identity.actorId(), identity.actorId()));
        if (!applicant) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /** 查询组织，不把不存在的组织与可访问性差异暴露给调用方。 */
    private OrganizationRow requireRow(long organizationId) {
        OrganizationRow row = jdbc.query("""
                SELECT id, organization_role, legal_name, registration_id, authorization_reference,
                       qualification_reference,
                       status, version, application_version, created_by, reviewed_by, review_reason
                FROM business_organization WHERE id = ? AND tenant_id = id
                """, result -> result.next() ? new OrganizationRow(result.getLong("id"),
                OrganizationRole.valueOf(result.getString("organization_role")),
                result.getString("legal_name"), result.getString("registration_id"),
                result.getString("authorization_reference"), result.getString("qualification_reference"),
                OrganizationStatus.valueOf(result.getString("status")),
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
    /**
     * 组织主体、审核状态和受控材料引用的数据库快照。
     * @param id 组织标识
     * @param role 用工方或劳务供应商角色
     * @param legalName 法定名称
     * @param registrationId 登记编号
     * @param authorizationReference 受控授权材料引用
     * @param qualificationReference 受控资质材料引用
     * @param status 当前准入状态
     * @param version 乐观并发版本
     * @param applicationVersion 申请资料版本
     * @param createdBy 初始申请账号
     * @param reviewedBy 最近审核账号
     * @param reviewReason 最近审核意见
     */
    private record OrganizationRow(long id, OrganizationRole role, String legalName, String registrationId,
                                   String authorizationReference, String qualificationReference,
                                   OrganizationStatus status, long version,
                                   long applicationVersion, long createdBy, Long reviewedBy, String reviewReason) {
        /** 将数据库快照映射为完整 API 返回值。 */
        private View view() {
            return view(true);
        }

        /**
         * 依据组织成员授权级别决定是否包含受控材料引用。
         * @param discloseReferences 当前账号是否为管理员或平台审核员
         * @return 对应授权范围的组织视图
         */
        private View view(boolean discloseReferences) {
            return new View(id, role, legalName, registrationId,
                    discloseReferences ? authorizationReference : null,
                    discloseReferences ? qualificationReference : null,
                    status, version, applicationVersion, createdBy, reviewedBy, reviewReason);
        }
    }

    /** 加入请求查询快照。 */
    private record MembershipRequestRow(long actorId, MembershipRole requestedRole,
                                        MembershipRequestStatus status, long version) {
    }

    /** 组织审核员认领任务后可读取的最小申请详情。 */
    public record OrganizationReviewSubject(String organizationId, String organizationRole, String legalName,
                                             String registrationId, String authorizationReference,
                                             String qualificationReference, String status,
                                             long applicationVersion, long entityVersion) {
        /**
         * @param organizationId 组织编号，以十进制字符串返回以避免浏览器精度损失
         * @param organizationRole 用工方或劳务供应商角色
         * @param legalName 本次提交的组织登记名称
         * @param registrationId 本次提交的登记编号
         * @param authorizationReference 申请人提交的授权材料引用
         * @param qualificationReference 申请人提交的资质材料引用
         * @param status 当前组织申请状态
         * @param applicationVersion 任务绑定的申请版本
         * @param entityVersion 组织资料乐观锁版本
         */
    }
}
