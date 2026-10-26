package com.company.labor.modules.demand;

import com.company.labor.modules.demand.DemandDtos.ReviewRequest;
import com.company.labor.modules.demand.DemandDtos.ReviewSubject;
import com.company.labor.modules.demand.DemandDtos.UpsertRequest;
import com.company.labor.modules.demand.DemandDtos.View;
import com.company.labor.modules.demand.DemandDtos.CorrectionItemRequest;
import com.company.labor.modules.identity.MembershipRole;
import com.company.labor.modules.identity.OrganizationAuthorization;
import com.company.labor.modules.identity.OrganizationRole;
import com.company.labor.modules.bidding.BiddingService;
import com.company.labor.modules.platform.ReviewCaseHistoryService;
import com.company.labor.modules.platform.ReviewCaseType;
import com.company.labor.modules.platform.PlatformTaskService;
import com.company.labor.modules.platform.PlatformTaskType;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.foundation.reliability.IdempotentExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/** 实现用工需求的草稿、审核、发布、暂停和撤回流程，并连接 BP-04 的交易门禁。 */
@Service
public class DemandService {
    /** 需求持久化入口。 */
    private final JdbcTemplate jdbc;
    /** 业务标识生成服务。 */
    private final SequenceIds ids;
    /** 组织成员及组织状态授权服务。 */
    private final OrganizationAuthorization authorization;
    /** 标识化审计入口。 */
    private final AuditRecorder audit;
    /** 同组织、操作者和操作范围内的事务幂等执行器。 */
    private final IdempotentExecutor idempotency;
    /** 已发布需求的跨组织字段白名单投递服务。 */
    private final DemandDeliveryService deliveries;
    /** 需求关闭时同步关闭未完成应标和授予的生命周期服务。 */
    private final BiddingService bidding;
    /** 首次提交时建立稳定事项并关联需求版本及审计事件。 */
    private final ReviewCaseHistoryService reviewHistory;
    /** 需求提交、认领及审核决定共享事务的岗位任务服务。 */
    private final PlatformTaskService platformTasks;

    /**
     * 创建需求业务服务。
     * @param jdbc 需求持久化入口
     * @param ids 业务标识生成服务
     * @param authorization 组织成员与状态授权服务
     * @param audit 追加式审计入口
     * @param idempotency 业务操作幂等执行器
     * @param deliveries 需求跨组织字段白名单投递服务
     * @param bidding 应标生命周期服务
     * @param reviewHistory 稳定事项与历史服务
     * @param platformTasks 版本绑定的审核任务服务
     */
    public DemandService(JdbcTemplate jdbc, SequenceIds ids, OrganizationAuthorization authorization,
                         AuditRecorder audit, IdempotentExecutor idempotency, DemandDeliveryService deliveries,
                         BiddingService bidding, ReviewCaseHistoryService reviewHistory,
                         PlatformTaskService platformTasks) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.authorization = authorization;
        this.audit = audit;
        this.idempotency = idempotency;
        this.deliveries = deliveries;
        this.bidding = bidding;
        this.reviewHistory = reviewHistory;
        this.platformTasks = platformTasks;
    }

    /**
     * 创建组织需求草稿。
     * @param organizationId 用工方组织标识
     * @param request 需求第一版内容
     * @return 新需求视图
     * @throws BusinessException 组织未准入或成员无编辑权限
     */
    @Transactional
    public View create(long organizationId, String idempotencyKey, UpsertRequest request) {
        requireEmployerEditor(organizationId);
        authorization.requireApproved(organizationId);
        validate(request);
        return idempotency.execute("business:demand:create:v1", idempotencyKey,
                Map.of("organizationId", organizationId, "request", request), View.class,
                () -> createOnce(organizationId, request));
    }

    /** 在幂等事务中创建需求及第一版内容。 */
    private View createOnce(long organizationId, UpsertRequest request) {
        long demandId = ids.nextId();
        long actor = ExecutionContext.require().actorId();
        jdbc.update("""
                INSERT INTO business_demand(id, organization_id, tenant_id, status, current_revision, created_by)
                VALUES (?, ?, ?, 'DRAFT', 1, ?)
                """, demandId, organizationId, organizationId, actor);
        insertRevision(demandId, organizationId, 1, request, actor);
        audit.record("demand:create", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, organizationId, 1, "DRAFT_CREATED"));
        return get(demandId);
    }

    /**
     * 修改需求并追加不可变版本；修改已发布条件时自动关闭旧应标并提交新版本审核。
     * @param demandId 需求标识
     * @param expectedRevision 客户端编辑时看到的版本
     * @param request 新版本内容
     * @return 新需求视图
     * @throws BusinessException 状态、权限或版本不允许修改
     */
    @Transactional
    public View update(long demandId, long expectedRevision, UpsertRequest request) {
        DemandRow demand = requireOwnerRow(demandId);
        requireEmployerEditor(demand.organizationId());
        authorization.requireApproved(demand.organizationId());
        boolean reopensReview = demand.status() == DemandStatus.OPEN_FOR_BID
                || demand.status() == DemandStatus.SUSPENDED;
        if (!reopensReview && demand.status() != DemandStatus.DRAFT
                && demand.status() != DemandStatus.NEEDS_CORRECTION) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (demand.revision() != expectedRevision) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        validate(request);
        long actor = ExecutionContext.require().actorId();
        insertRevision(demandId, demand.organizationId(), expectedRevision + 1, request, actor);
        int updated = jdbc.update("""
                UPDATE business_demand
                SET current_revision = ?,
                    status = CASE WHEN status IN ('OPEN_FOR_BID', 'SUSPENDED') THEN 'PENDING_REVIEW' ELSE status END,
                    submitted_by = CASE WHEN status IN ('OPEN_FOR_BID', 'SUSPENDED') THEN ? ELSE submitted_by END,
                    review_reason = CASE WHEN status IN ('OPEN_FOR_BID', 'SUSPENDED') THEN NULL ELSE review_reason END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND current_revision = ?
                  AND status IN ('DRAFT', 'NEEDS_CORRECTION', 'OPEN_FOR_BID', 'SUSPENDED')
                """, expectedRevision + 1, actor, demandId, demand.organizationId(), expectedRevision);
        ensureUpdated(updated);
        if (reopensReview) {
            bidding.closeForDemand(demandId);
            long reviewCaseId = reviewHistory.openOnSubmission(ReviewCaseType.DEMAND, demandId,
                    demand.organizationId(), expectedRevision + 1, actor);
            platformTasks.openReviewTaskOnSubmission(reviewCaseId, ReviewCaseType.DEMAND, demandId,
                    demand.organizationId(), expectedRevision + 1);
        }
        audit.record("demand:update", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        expectedRevision + 1, reopensReview ? "RESUBMITTED_FOR_REVIEW" : "VERSION_CREATED"));
        return get(demandId);
    }

    /**
     * 提交需求草稿供平台审核。
     * @param demandId 需求标识
     * @return 待审核需求视图
     * @throws BusinessException 状态、组织或成员权限不允许提交
     */
    @Transactional
    public View submit(long demandId) {
        DemandRow demand = requireOwnerRow(demandId);
        requireEmployerEditor(demand.organizationId());
        authorization.requireApproved(demand.organizationId());
        if (demand.status() != DemandStatus.DRAFT && demand.status() != DemandStatus.NEEDS_CORRECTION) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        int updated = jdbc.update("""
                UPDATE business_demand
                SET status = 'PENDING_REVIEW', submitted_by = ?, review_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND current_revision = ?
                  AND (status = 'DRAFT' OR (status = 'NEEDS_CORRECTION'
                      AND current_revision > COALESCE(reviewed_revision, 0)))
                """, ExecutionContext.require().actorId(), demandId, demand.organizationId(), demand.revision());
        ensureUpdated(updated);
        long reviewCaseId = reviewHistory.openOnSubmission(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                demand.revision(), ExecutionContext.require().actorId());
        platformTasks.openReviewTaskOnSubmission(reviewCaseId, ReviewCaseType.DEMAND, demandId,
                demand.organizationId(), demand.revision());
        audit.record("demand:submit", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        demand.revision(), "APPLICATION_SUBMITTED"));
        return get(demandId);
    }

    /**
     * 执行平台需求审核；审核人不能是需求创建人。
     * @param demandId 需求标识
     * @param request 审核决定和理由
     * @return 审核后的需求视图
     * @throws BusinessException 缺少平台权限、状态不正确或利益冲突
     */
    @Transactional
    public View review(long demandId, ReviewRequest request) {
        authorization.requirePlatformPermission("platform:demands:review");
        DemandRow demand = requireRow(demandId);
        long actor = ExecutionContext.require().actorId();
        if (demand.status() != DemandStatus.PENDING_REVIEW || demand.createdBy() == actor
                || Objects.equals(demand.submittedBy(), actor)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        PlatformTaskService.TaskRef taskRef = platformTasks.requireClaimedReviewTask(
                ReviewCaseType.DEMAND, demandId, demand.revision());
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (request.decision() != ReviewRequest.Decision.APPROVE && reason == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        List<CorrectionItemRequest> correctionItems = request.correctionItems() == null
                ? List.of() : request.correctionItems();
        if (request.decision() == ReviewRequest.Decision.NEEDS_CORRECTION
                && (correctionItems.isEmpty() || correctionItems.size() > 20)) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (request.decision() != ReviewRequest.Decision.NEEDS_CORRECTION && !correctionItems.isEmpty()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        Set<String> correctionCodes = new HashSet<>();
        for (CorrectionItemRequest item : correctionItems) {
            if (item == null || item.itemCode() == null || item.title() == null || item.description() == null
                    || item.applicantAction() == null || item.itemCode().trim().isEmpty()
                    || item.title().trim().isEmpty() || item.description().trim().isEmpty()
                    || item.applicantAction().trim().isEmpty()
                    || !correctionCodes.add(item.itemCode().trim())) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
        }
        DemandStatus next = switch (request.decision()) {
            case APPROVE -> DemandStatus.APPROVED_PENDING_PUBLISH;
            case NEEDS_CORRECTION -> DemandStatus.NEEDS_CORRECTION;
            case REJECT -> DemandStatus.REJECTED;
        };
        int updated = jdbc.update("""
                UPDATE business_demand
                SET status = ?, reviewed_by = ?, review_reason = ?, reviewed_revision = current_revision,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW'
                  AND current_revision = ?
                """, next.name(), actor, reason, demandId, demand.organizationId(), demand.revision());
        ensureUpdated(updated);
        jdbc.update("""
                INSERT INTO business_review_decision
                    (tenant_id, resource_type, resource_id, resource_version, from_status, decision,
                     to_status, reviewer_id, reason)
                VALUES (?, 'DEMAND', ?, ?, ?, ?, ?, ?, ?)
                """, demand.organizationId(), demandId, demand.revision(), demand.status().name(),
                request.decision().name(), next.name(), actor, reason);
        if (request.decision() == ReviewRequest.Decision.NEEDS_CORRECTION) {
            reviewHistory.appendCorrectionItems(ReviewCaseType.DEMAND, demandId, demand.organizationId(), taskRef,
                    correctionItems.stream().map(item -> new ReviewCaseHistoryService.CorrectionItemInput(
                            item.itemCode(), item.title(), item.description(), item.applicantAction())).toList(), actor);
        }
        audit.record("demand:review", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        demand.revision(), request.decision().name()));
        platformTasks.completeReviewTask(taskRef, request.decision().name());
        return requireRow(demandId).view();
    }

    /**
     * 发布已审核需求并开放应标读取。
     * @param demandId 需求标识
     * @return 开放中的需求视图
     * @throws BusinessException 成员无发布权限、组织失效、状态或窗口不允许发布
     */
    @Transactional
    public View publish(long demandId) {
        DemandRow demand = requireOwnerRow(demandId);
        requirePublisher(demand.organizationId());
        authorization.requireApproved(demand.organizationId());
        if (demand.status() != DemandStatus.APPROVED_PENDING_PUBLISH
                || !isAfterNow(demand.bidDeadline()) || demand.confirmationDeadline() == null
                || !demand.confirmationDeadline().isAfter(demand.bidDeadline())) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        int updated = jdbc.update("""
                UPDATE business_demand
                SET status = 'OPEN_FOR_BID', published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND status = 'APPROVED_PENDING_PUBLISH'
                  AND current_revision = ?
                """, demandId, demand.organizationId(), demand.revision());
        ensureUpdated(updated);
        deliveries.deliver(demandId, demand.organizationId(), demand.revision());
        audit.record("demand:publish", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        demand.revision(), "DEMAND_PUBLISHED"));
        return get(demandId);
    }

    /**
     * 读取已认领需求审核任务绑定的版本字段；响应不包含应标、供应商资料或报价记录。
     * @param taskId 当前审核员已认领的需求审核任务
     * @return 当前审核版本的需求字段白名单
     * @throws BusinessException 任务无效、类型不符、版本过期或审核员存在回避冲突
     */
    @Transactional
    public ReviewSubject reviewSubject(long taskId) {
        var scope = requireDemandReviewScope(taskId);
        DemandRow demand = requireRow(scope.resourceId());
        if (demand.organizationId() != scope.tenantId()
                || demand.revision() != scope.resourceVersion() || demand.status() != DemandStatus.PENDING_REVIEW) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        return demand.reviewSubject();
    }

    /**
     * 按已认领的需求任务提交审核决定，确保路由编号不能被替换为其他需求编号。
     * @param taskId 当前审核员已认领的需求审核任务
     * @param request 审核决定、理由和逐项补正清单
     * @return 审核后的需求当前状态
     * @throws BusinessException 任务无效、版本过期、权限不足或审核字段不合法
     */
    @Transactional
    public View reviewTask(long taskId, ReviewRequest request) {
        ReviewSubject subject = reviewSubject(taskId);
        return review(Long.parseLong(subject.demandId()), request);
    }

    /**
     * 暂停已发布需求；暂停期间供应商可以撤回已有应标，但所有新增、修改、重提、选标和确认均被拒绝。
     * @param demandId 需求标识
     * @return 暂停后的需求视图
     * @throws BusinessException 当前成员、需求状态或组织状态不允许暂停
     */
    @Transactional
    public View suspend(long demandId) {
        DemandRow demand = requireOwnerRow(demandId);
        requirePublisher(demand.organizationId());
        authorization.requireApproved(demand.organizationId());
        if (demand.status() != DemandStatus.OPEN_FOR_BID) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        ensureUpdated(jdbc.update("""
                UPDATE business_demand SET status = 'SUSPENDED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND status = 'OPEN_FOR_BID'
                """, demandId, demand.organizationId()));
        audit.record("demand:suspend", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        demand.revision(), "DEMAND_SUSPENDED"));
        return get(demandId);
    }

    /**
     * 撤回并关闭需求；未完成应标和供应商确认被关闭，已生成的 BP-05 快照保持不变。
     * @param demandId 需求标识
     * @return 关闭后的需求视图
     * @throws BusinessException 当前成员、需求状态或组织状态不允许撤回
     */
    @Transactional
    public View close(long demandId) {
        DemandRow demand = requireOwnerRow(demandId);
        requirePublisher(demand.organizationId());
        authorization.requireApproved(demand.organizationId());
        if (demand.status() != DemandStatus.OPEN_FOR_BID && demand.status() != DemandStatus.SUSPENDED) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        ensureUpdated(jdbc.update("""
                UPDATE business_demand SET status = 'CLOSED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND status IN ('OPEN_FOR_BID', 'SUSPENDED')
                """, demandId, demand.organizationId()));
        bidding.closeForDemand(demandId);
        audit.record("demand:close", "demand", Long.toString(demandId), "SUCCESS",
                reviewHistory.auditDetails(ReviewCaseType.DEMAND, demandId, demand.organizationId(),
                        demand.revision(), "DEMAND_CLOSED"));
        return get(demandId);
    }

    /**
     * 读取需求；所属组织成员可读全部字段，合格劳务公司只能读开放需求。
     * @param demandId 需求标识
     * @return 需求视图
     * @throws BusinessException 需求不存在或当前身份无权读取
     */
    public View get(long demandId) {
        long tenantId = ExecutionContext.requireTenant();
        DemandRow owned = findRow(demandId, tenantId);
        if (owned != null) {
            if (!isOrganizationMember(tenantId)) {
                throw new BusinessException(PlatformError.ACCESS_DENIED);
            }
            return owned.view();
        }
        if (!supplierMembershipExists()) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return deliveredView(demandId);
    }

    /**
     * 查询当前劳务公司成员可以看到的开放需求，不返回联系人或私密报价。
     * @return 开放需求摘要列表
     * @throws BusinessException 当前身份不是已准入劳务公司成员
     */
    public List<View> openForBid() {
        if (!supplierMembershipExists()) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        long tenantId = ExecutionContext.requireTenant();
        return jdbc.query("""
                SELECT dd.demand_id, dd.source_tenant_id, dd.revision, dd.title, dd.quantity, dd.quantity_unit,
                       dd.starts_at, dd.ends_at, dd.bid_deadline, dd.confirmation_deadline,
                       dd.service_scope, dd.location, dd.target, dd.pricing_mode,
                       dd.measurement_basis, dd.settlement_cycle, dd.selection_rule
                FROM business_demand_delivery dd
                JOIN business_demand d ON d.id = dd.demand_id AND d.tenant_id = dd.source_tenant_id
                WHERE dd.tenant_id = ? AND dd.bid_deadline > CURRENT_TIMESTAMP
                  AND d.status = 'OPEN_FOR_BID' AND d.current_revision = dd.revision
                ORDER BY dd.bid_deadline, dd.demand_id
                LIMIT 100
                """, (result, row) -> deliveredRow(result).view(), tenantId);
    }

    /**
     * 查询用工方当前组织的需求列表。
     * @param organizationId 当前组织标识
     * @return 当前组织最近更新的需求，最多返回 100 条
     * @throws BusinessException 身份或组织成员权限不足
     */
    public List<View> listForOrganization(long organizationId) {
        ExecutionContext.checkPermission("organization:demands:read");
        OrganizationAuthorization.Membership membership = authorization.membership(organizationId,
                Set.of(MembershipRole.ORGANIZATION_ADMIN, MembershipRole.DEMAND_EDITOR,
                        MembershipRole.DEMAND_PUBLISHER, MembershipRole.DEMAND_SELECTOR));
        if (membership.organizationRole() != OrganizationRole.EMPLOYER) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return jdbc.query("""
                SELECT d.id, d.organization_id, d.status, d.current_revision, d.created_by, d.submitted_by,
                       d.review_reason, r.title, r.quantity, r.quantity_unit, r.starts_at, r.ends_at,
                       r.bid_deadline, r.confirmation_deadline, r.service_scope, r.location,
                       r.related_party_relation, r.target, r.pricing_mode, r.measurement_basis,
                       r.settlement_cycle, r.selection_rule
                FROM business_demand d
                JOIN business_demand_revision r ON r.demand_id = d.id
                    AND r.tenant_id = d.tenant_id AND r.revision = d.current_revision
                WHERE d.tenant_id = ? AND d.organization_id = ?
                ORDER BY d.updated_at DESC, d.id DESC
                LIMIT 100
                """, (result, row) -> mapRow(result).view(), organizationId, organizationId);
    }

    /**
     * 查询需求审核岗位最小任务队列，不返回未认领事项详情或材料引用。
     * @return 当前审核岗位的任务摘要
     * @throws BusinessException 当前身份没有平台需求审核权限
     */
    public List<PlatformTaskService.QueueItem> pendingReviews() {
        return platformTasks.queue(PlatformTaskType.DEMAND_REVIEW);
    }

    /**
     * 校验平台任务为当前账号持有的有效需求审核任务。
     * @param taskId 平台审核任务编号
     * @return 通过岗位、认领、版本和回避校验的需求范围
     * @throws BusinessException 任务类型不符、无有效认领或权限不足
     */
    private PlatformTaskService.EvidenceTaskScope requireDemandReviewScope(long taskId) {
        PlatformTaskService.EvidenceTaskScope scope = platformTasks.requireEvidenceTask(taskId);
        if (!"DEMAND".equals(scope.resourceType()) || !"DEMAND_REVIEW".equals(scope.taskType())) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return scope;
    }

    /** 读取当前供应商租户的单条投递快照，避免跨租户读取需求源表。 */
    private View deliveredView(long demandId) {
        long tenantId = ExecutionContext.requireTenant();
        DemandRow delivered = jdbc.query("""
                SELECT demand_id, source_tenant_id, revision, title, quantity, quantity_unit,
                       starts_at, ends_at, bid_deadline, confirmation_deadline, service_scope, location, target,
                       pricing_mode, measurement_basis, settlement_cycle, selection_rule
                FROM business_demand_delivery
                WHERE tenant_id = ? AND demand_id = ? AND bid_deadline > CURRENT_TIMESTAMP
                  AND EXISTS (
                      SELECT 1 FROM business_demand d
                      WHERE d.id = business_demand_delivery.demand_id
                        AND d.tenant_id = business_demand_delivery.source_tenant_id
                        AND d.status = 'OPEN_FOR_BID'
                        AND d.current_revision = business_demand_delivery.revision
                  )
                """, result -> result.next() ? deliveredRow(result) : null, tenantId, demandId);
        if (delivered == null) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return delivered.supplierView();
    }

    /** 要求成员具有需求编辑能力并返回组织状态。 */
    private void requireEmployerEditor(long organizationId) {
        ExecutionContext.checkPermission("organization:demands:write");
        OrganizationAuthorization.Membership membership = authorization.membership(
                organizationId, OrganizationAuthorization.Membership.adminOr(MembershipRole.DEMAND_EDITOR));
        if (membership.organizationRole() != OrganizationRole.EMPLOYER) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /** 要求成员具有需求发布能力。 */
    private void requirePublisher(long organizationId) {
        ExecutionContext.checkPermission("organization:demands:publish");
        OrganizationAuthorization.Membership membership = authorization.membership(
                organizationId, OrganizationAuthorization.Membership.adminOr(MembershipRole.DEMAND_PUBLISHER));
        if (membership.organizationRole() != OrganizationRole.EMPLOYER) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /** 判断当前身份是否为目标组织成员。 */
    private boolean isOrganizationMember(long organizationId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() == null || identity.tenantId() != organizationId) {
            return false;
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_membership
                WHERE organization_id = ? AND tenant_id = ? AND actor_id = ? AND status = 'ACTIVE'
                """, Integer.class, organizationId, organizationId, identity.actorId()) > 0;
    }

    /** 判断当前身份是否属于已准入的劳务公司组织。 */
    private boolean supplierMembershipExists() {
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() == null) {
            return false;
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_membership m
                JOIN business_organization o ON o.id = m.organization_id
                WHERE m.actor_id = ? AND m.tenant_id = ? AND m.organization_id = ? AND m.status = 'ACTIVE'
                  AND o.organization_role = 'LABOR_SUPPLIER' AND o.status = 'APPROVED'
                """, Integer.class, identity.actorId(), identity.tenantId(), identity.tenantId()) > 0;
    }

    /** 校验需求的结构化必填条件和时间窗口。 */
    private void validate(UpsertRequest request) {
        if (!request.endsAt().isAfter(request.startsAt()) || !request.bidDeadline().isBefore(request.startsAt())
                || !request.confirmationDeadline().isAfter(request.bidDeadline())) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /** 写入只追加的需求版本。 */
    private void insertRevision(long demandId, long tenantId, long revision, UpsertRequest request, long actor) {
        jdbc.update("""
                INSERT INTO business_demand_revision
                    (demand_id, tenant_id, revision, related_party_relation, service_scope, location, starts_at, ends_at,
                     title, quantity, quantity_unit, target, pricing_mode, measurement_basis, settlement_cycle,
                     bid_deadline, confirmation_deadline, selection_rule, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, demandId, tenantId, revision, request.relatedPartyRelation().trim(), request.serviceScope().trim(),
                request.location().trim(), Timestamp.from(request.startsAt()), Timestamp.from(request.endsAt()),
                request.title().trim(),
                request.quantity(), request.quantityUnit().trim(), request.target().trim(),
                request.pricingMode().trim(), request.measurementBasis().trim(), request.settlementCycle().trim(),
                Timestamp.from(request.bidDeadline()), Timestamp.from(request.confirmationDeadline()),
                request.selectionRule().trim(), actor);
    }

    /** 查询需求及当前版本；不区分不存在和无权资源以避免泄露存在性。 */
    private DemandRow requireRow(long demandId) {
        DemandRow row = findRow(demandId, null);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 查找当前组织内的需求；空组织上下文不能用于业务写入。 */
    private DemandRow requireOwnerRow(long demandId) {
        return requireRow(demandId, ExecutionContext.requireTenant());
    }

    /** 按租户读取需求；仅平台审核路径可使用不带租户条件的查询。 */
    private DemandRow requireRow(long demandId, long tenantId) {
        DemandRow row = findRow(demandId, tenantId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 查询指定需求及其版本；tenantId 为空只用于已校验平台审核权限的路径。 */
    private DemandRow findRow(long demandId, Long tenantId) {
        String tenantPredicate = tenantId == null ? "d.id = ?" : "d.id = ? AND d.tenant_id = ?";
        Object[] parameters = tenantId == null ? new Object[]{demandId} : new Object[]{demandId, tenantId};
        DemandRow row = jdbc.query("""
                SELECT d.id, d.organization_id, d.status, d.current_revision, d.created_by, d.submitted_by,
                       d.review_reason,
                       r.title, r.quantity, r.quantity_unit, r.starts_at, r.ends_at, r.bid_deadline,
                       r.confirmation_deadline,
                       r.service_scope, r.location, r.related_party_relation, r.target, r.pricing_mode,
                       r.measurement_basis, r.settlement_cycle, r.selection_rule
                FROM business_demand d
                JOIN business_demand_revision r ON r.demand_id = d.id
                    AND r.tenant_id = d.tenant_id AND r.revision = d.current_revision
                WHERE %s AND d.tenant_id = d.organization_id
                """.formatted(tenantPredicate), result -> result.next() ? mapRow(result) : null, parameters);
        return row;
    }

    /** 把 JDBC 行映射为需求快照。 */
    private DemandRow mapRow(ResultSet result) throws SQLException {
        return new DemandRow(result.getLong("id"), result.getLong("organization_id"),
                DemandStatus.valueOf(result.getString("status")), result.getLong("current_revision"),
                result.getLong("created_by"), result.getObject("submitted_by", Long.class),
                result.getString("review_reason"), result.getString("title"),
                result.getBigDecimal("quantity"), result.getString("quantity_unit"), instant(result, "starts_at"),
                instant(result, "ends_at"), instant(result, "bid_deadline"), result.getString("service_scope"),
                result.getString("location"), result.getString("related_party_relation"), result.getString("target"),
                result.getString("pricing_mode"), result.getString("measurement_basis"),
                result.getString("settlement_cycle"), instantOrNull(result, "confirmation_deadline"),
                result.getString("selection_rule"));
    }

    /** 将供应商租户中的投递快照映射为需求行。 */
    private DemandRow deliveredRow(ResultSet result) throws SQLException {
        return new DemandRow(result.getLong("demand_id"), result.getLong("source_tenant_id"),
                DemandStatus.OPEN_FOR_BID, result.getLong("revision"), 0L, null, null,
                result.getString("title"), result.getBigDecimal("quantity"), result.getString("quantity_unit"),
                instant(result, "starts_at"), instant(result, "ends_at"), instant(result, "bid_deadline"),
                result.getString("service_scope"), result.getString("location"), null,
                result.getString("target"), result.getString("pricing_mode"),
                result.getString("measurement_basis"), result.getString("settlement_cycle"),
                instantOrNull(result, "confirmation_deadline"), result.getString("selection_rule"));
    }

    /** 判断应标截止时间尚未到达。 */
    private boolean isAfterNow(Instant deadline) {
        return deadline.isAfter(Instant.now());
    }

    /** 将时间戳安全转换为 UTC Instant。 */
    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp.toInstant();
    }

    /** 将兼容历史投递记录的可空时间戳转换为 UTC Instant。 */
    private Instant instantOrNull(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 保证乐观版本更新影响了唯一预期行。 */
    private void ensureUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
    }

    /** 需求及当前版本的不可变内部快照。 */
    private record DemandRow(long id, long organizationId, DemandStatus status, long revision, long createdBy,
                             Long submittedBy,
                             String reviewReason, String title, BigDecimal quantity, String quantityUnit,
                             Instant startsAt, Instant endsAt, Instant bidDeadline, String serviceScope,
                             String location, String relatedPartyRelation, String target, String pricingMode,
                             String measurementBasis, String settlementCycle, Instant confirmationDeadline,
                             String selectionRule) {
        /** 映射为公共需求视图。 */
        private View view() {
            return new View(id, organizationId, revision, status, title, quantity, quantityUnit, startsAt, endsAt,
                    bidDeadline, serviceScope, location, relatedPartyRelation, target, pricingMode,
                    measurementBasis, settlementCycle, confirmationDeadline, selectionRule, reviewReason);
        }

        /** 返回供应商可见投标摘要，不披露用工主体关系说明和内部审核理由。 */
        private View supplierView() {
            return new View(id, organizationId, revision, status, title, quantity, quantityUnit, startsAt, endsAt,
                    bidDeadline, serviceScope, location, null, target, pricingMode,
                    measurementBasis, settlementCycle, confirmationDeadline, selectionRule, null);
        }

        /**
         * 构造不含组织标识、应标数据、供应商资料和实际报价的审核字段白名单。
         * @return 需求审核员可见的当前版本字段
         */
        private ReviewSubject reviewSubject() {
            return new ReviewSubject(Long.toString(id), revision, status.name(), title, quantity, quantityUnit,
                    startsAt, endsAt, bidDeadline, serviceScope, location, relatedPartyRelation, target,
                    pricingMode, measurementBasis, settlementCycle, confirmationDeadline, selectionRule,
                    reviewReason);
        }
    }
}
