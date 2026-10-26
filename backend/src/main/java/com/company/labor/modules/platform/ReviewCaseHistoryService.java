package com.company.labor.modules.platform;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.Event;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.CorrectionItem;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.Version;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.Appeal;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.View;
import com.company.labor.modules.platform.PlatformTaskService.TaskRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 维护稳定审核事项关联并按申请方或平台授权提供只读版本历史。 */
@Service
public class ReviewCaseHistoryService {
    /** 申请方可见的有限业务事件集合，不包含审计查询、拒绝尝试或内部访问。 */
    private static final String APPLICANT_ACTIONS_SQL = "'organization:submit', 'organization:update', "
            + "'organization:review', 'demand:submit', 'demand:update', 'demand:review', "
            + "'demand:publish', 'demand:suspend', 'demand:close'";
    /** 审核事项、业务版本和审计事件的唯一数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 平台领域主键生成器，使用与业务对象相同的 PostgreSQL 序列。 */
    private final SequenceIds ids;
    /** 事项历史读取自身也必须记录的追加式审计服务。 */
    private final AuditRecorder audit;

    /**
     * 装配数据库、稳定标识与审计依赖。
     * @param jdbc 数据库访问模板
     * @param ids PostgreSQL 序列编号服务
     * @param audit 追加式审计服务
     */
    public ReviewCaseHistoryService(JdbcTemplate jdbc, SequenceIds ids, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.audit = audit;
    }

    /**
     * 在首次提交的业务事务中创建稳定事项映射；迁移前事项保留其历史覆盖缺口。
     * @param type 组织或需求资源类型
     * @param resourceId 业务资源编号
     * @param tenantId 资源归属组织编号
     * @param resourceVersion 本次提交的业务版本
     * @param actorId 当前提交账号
     * @return 稳定审核事项编号
     * @throws IllegalArgumentException 资源、组织、版本或账号编号不合法
     * @throws BusinessException 资源版本与数据库当前有效版本不一致
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long openOnSubmission(ReviewCaseType type, long resourceId, long tenantId,
                                 long resourceVersion, long actorId) {
        validatePositive(resourceId, tenantId, resourceVersion, actorId);
        if (!isCurrentSubmittedVersion(type, resourceId, tenantId, resourceVersion)) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        Long current = findCaseId(type, resourceId, tenantId);
        if (current != null) {
            return current;
        }
        long candidate = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_review_case
                    (id, tenant_id, resource_type, resource_id, initial_version, opened_by, opened_at,
                     history_coverage)
                VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), 'COMPLETE')
                ON CONFLICT (tenant_id, resource_type, resource_id) DO NOTHING
                """, candidate, tenantId, type.name(), resourceId, resourceVersion, actorId);
        Long created = findCaseId(type, resourceId, tenantId);
        if (created == null) {
            throw new IllegalStateException("Review case creation did not produce a stable association");
        }
        return created;
    }

    /**
     * 为当前业务动作创建审计关联；草稿阶段及迁移前事件保持事项编号为空。
     * @param type 组织或需求资源类型
     * @param resourceId 业务资源编号
     * @param tenantId 资源归属组织编号
     * @param resourceVersion 本次动作涉及的版本
     * @param reasonCode 稳定原因码，不包含自由文本
     * @return 可直接交给追加式审计写入器的详情
     */
    public AuditRecorder.AuditDetails auditDetails(ReviewCaseType type, long resourceId, long tenantId,
                                                   long resourceVersion, String reasonCode) {
        Long caseId = findCaseId(type, resourceId, tenantId);
        return new AuditRecorder.AuditDetails(caseId == null ? null : Long.toString(caseId), null, null, null,
                resourceVersion, null, reasonCode, null);
    }

    /**
     * 在当前审核决定事务中追加绑定任务、事项和版本的补正清单及其审计事件。
     * @param type 组织或需求资源类型
     * @param resourceId 被审核资源编号
     * @param tenantId 资源归属组织编号
     * @param taskRef 当前决定所用的平台审核任务
     * @param items 对申请方公开的问题与动作
     * @param actorId 记录补正清单的审核员
     * @throws BusinessException 任务、事项、租户或版本关联不一致
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendCorrectionItems(ReviewCaseType type, long resourceId, long tenantId, TaskRef taskRef,
                                      List<CorrectionItemInput> items, long actorId) {
        validatePositive(resourceId, tenantId, taskRef.resourceVersion(), actorId);
        PlatformTaskType expectedTaskType = type == ReviewCaseType.ORGANIZATION
                ? PlatformTaskType.ORGANIZATION_REVIEW : PlatformTaskType.DEMAND_REVIEW;
        Long caseId = findCaseId(type, resourceId, tenantId);
        boolean taskMatches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM platform_review_task
                    WHERE id = ? AND tenant_id = ? AND review_case_id = ? AND task_type = ?
                      AND resource_type = ? AND resource_id = ? AND resource_version = ?)
                """, Boolean.class, taskRef.taskId(), tenantId, taskRef.reviewCaseId(), expectedTaskType.name(),
                type.name(), resourceId, taskRef.resourceVersion()));
        if (taskRef.taskType() != expectedTaskType || caseId == null || caseId != taskRef.reviewCaseId()
                || !taskMatches) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        for (CorrectionItemInput item : items) {
            jdbc.update("""
                    INSERT INTO platform_review_correction_item
                        (id, tenant_id, review_case_id, task_id, resource_type, resource_id, resource_version,
                         item_code, title, description, applicant_action, actor_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ids.nextId(), tenantId, taskRef.reviewCaseId(), taskRef.taskId(), type.name(), resourceId,
                    taskRef.resourceVersion(), item.itemCode().trim(), item.title().trim(),
                    item.description().trim(), item.applicantAction().trim(), actorId);
        }
        audit.record("platform:review:correction-items:recorded", "platform_review_correction_item",
                Long.toString(resourceId), "SUCCESS",
                auditDetails(type, resourceId, tenantId, taskRef.resourceVersion(), "CORRECTION_ITEMS_RECORDED"));
    }

    /**
     * 读取组织管理员所属组织的事项历史；普通成员和平台审核员不能借此绕过 G5 认领门禁。
     * @param organizationId 组织编号
     * @return 仅含该组织已提交申请的公开历史
     * @throws BusinessException 当前账号不是组织有效管理员或事项尚未提交
     */
    @Transactional
    public View applicantOrganizationHistory(long organizationId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        boolean administrator = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM business_membership
                    WHERE organization_id = ? AND tenant_id = ? AND actor_id = ?
                      AND member_role = 'ORGANIZATION_ADMIN' AND status = 'ACTIVE'
                )
                """, Boolean.class, organizationId, organizationId, identity.actorId()));
        if ("PLATFORM".equals(identity.contextType()) || !administrator) {
            audit.recordSecurityEvent("platform:review_case:history:denied", "review_case",
                    Long.toString(organizationId), "DENIED", "ORGANIZATION_ADMIN_REQUIRED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        CaseRow reviewCase = requireCase(ReviewCaseType.ORGANIZATION, organizationId, organizationId);
        View view = buildView(reviewCase, false);
        recordRead(reviewCase, "APPLICANT_HISTORY");
        return view;
    }

    /**
     * 读取当前用工组织成员所属需求的公开历史，不读取供应商投递副本或其他组织事项。
     * @param demandId 需求编号
     * @return 仅含该需求已提交版本及公开审核结果的历史
     * @throws BusinessException 当前身份不是该用工方组织有效成员或事项尚未提交
     */
    @Transactional
    public View applicantDemandHistory(long demandId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        long tenantId = ExecutionContext.requireTenant();
        boolean employerMember = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM business_demand d
                    JOIN business_organization o ON o.id = d.organization_id AND o.tenant_id = d.tenant_id
                    JOIN business_membership m ON m.organization_id = d.organization_id AND m.tenant_id = d.tenant_id
                    WHERE d.id = ? AND d.tenant_id = ? AND o.organization_role = 'EMPLOYER'
                      AND m.actor_id = ? AND m.status = 'ACTIVE'
                      AND m.member_role IN ('ORGANIZATION_ADMIN', 'DEMAND_EDITOR', 'DEMAND_PUBLISHER', 'DEMAND_SELECTOR')
                )
                """, Boolean.class, demandId, tenantId, identity.actorId()));
        if (!employerMember) {
            audit.recordSecurityEvent("platform:review_case:history:denied", "review_case",
                    Long.toString(demandId), "DENIED", "EMPLOYER_MEMBERSHIP_REQUIRED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        CaseRow reviewCase = requireCase(ReviewCaseType.DEMAND, demandId, tenantId);
        View view = buildView(reviewCase, false);
        recordRead(reviewCase, "APPLICANT_HISTORY");
        return view;
    }

    /** 只为当前持有有效任务的审核员读取同一事项内部历史，不开放给未认领队列。
     * @param taskId 岗位任务编号
     * @return 仅限该审核事项的版本、决定和内部审计时间线
     * @throws BusinessException 当前账号不是任务有效认领人或事项不存在
     */
    @Transactional
    View claimedTaskHistory(long taskId) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        CaseRow reviewCase = jdbc.query("""
                SELECT c.id, c.tenant_id, c.resource_type, c.resource_id, c.initial_version, c.history_coverage
                FROM platform_review_task t
                JOIN platform_task_claim claim ON claim.task_id = t.id
                    AND claim.actor_id = ? AND claim.status = 'ACTIVE'
                JOIN platform_review_case c ON c.id = t.review_case_id AND c.tenant_id = t.tenant_id
                WHERE t.id = ? AND t.status = 'CLAIMED'
                """, result -> result.next() ? mapCase(result) : null, identity.actorId(), taskId);
        if (reviewCase == null) {
            audit.recordSecurityEvent("platform:task:detail:read", "platform_review_task", Long.toString(taskId),
                    "DENIED", "ACTIVE_CLAIM_REQUIRED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        View view = buildView(reviewCase, true);
        recordRead(reviewCase, "CLAIMED_REVIEW_TASK");
        return view;
    }

    /**
     * 供独立监察岗位跨组织读取完整事项历史；调用方必须先完成实时平台权限校验。
     * @param reviewCaseId 稳定审核事项编号
     * @return 包含版本、决定、补正项和全部事项审计事件的内部历史
     * @throws BusinessException 事项不存在或关联业务资源已不存在
     */
    @Transactional
    public View platformAuditHistory(long reviewCaseId) {
        if (reviewCaseId <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        CaseRow reviewCase = jdbc.query("""
                SELECT id, tenant_id, resource_type, resource_id, initial_version, history_coverage
                FROM platform_review_case
                WHERE id = ?
                """, result -> result.next() ? mapCase(result) : null, reviewCaseId);
        if (reviewCase == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        View view = buildView(reviewCase, true);
        recordRead(reviewCase, "PLATFORM_AUDIT_HISTORY");
        return view;
    }

    /**
     * 组装事项版本、决定、当前未结补正项和按权限过滤的审计时间线。
     * @param reviewCase 稳定事项映射
     * @param internalView 是否包含内部操作者和全部事项审计事件
     * @return 按版本和事件时间升序排列的历史视图
     */
    private View buildView(CaseRow reviewCase, boolean internalView) {
        ResourceState resource = loadCurrentResource(reviewCase);
        if (resource == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        List<Version> versions = loadVersions(reviewCase);
        List<Appeal> appeals = loadAppeals(reviewCase);
        List<Event> events = loadEvents(reviewCase, internalView);
        List<CorrectionItem> currentCorrections = resource.status().equals("NEEDS_CORRECTION")
                ? loadCorrectionItems(reviewCase, resource.currentVersion()) : List.of();
        List<String> unresolvedItems = currentCorrections.isEmpty() && resource.status().equals("NEEDS_CORRECTION")
                && resource.reviewReason() != null ? List.of(resource.reviewReason())
                : currentCorrections.stream().map(item -> item.title() + "：" + item.applicantAction()).toList();
        return new View(Long.toString(reviewCase.id()), reviewCase.type().name(),
                Long.toString(reviewCase.resourceId()), resource.status(), resource.currentVersion(),
                reviewCase.historyCoverage(), List.copyOf(versions), List.copyOf(appeals), List.copyOf(events),
                List.copyOf(unresolvedItems), internalView);
    }

    /**
     * 只读取当前事项的复核处理状态与最终意见，不向申请方开放复核员身份或内部任务记录。
     * @param reviewCase 已经完成申请方或内部岗位授权的事项
     * @return 按申请时间排序的独立复核摘要
     */
    private List<Appeal> loadAppeals(CaseRow reviewCase) {
        return jdbc.query("""
                SELECT a.id, a.resource_version, a.status, r.outcome, r.reviewed_at
                FROM platform_appeal_case a
                LEFT JOIN platform_appeal_review_result r ON r.appeal_case_id = a.id
                WHERE a.review_case_id = ? AND a.tenant_id = ?
                ORDER BY a.created_at, a.id
                """, (result, row) -> new Appeal(Long.toString(result.getLong("id")),
                result.getLong("resource_version"), result.getString("status"),
                result.getString("outcome"), instantOrNull(result, "reviewed_at")),
                reviewCase.id(), reviewCase.tenantId());
    }

    /**
     * 读取事项所关联的组织或需求当前业务状态，不将状态副本保存在事项表。
     * @param reviewCase 稳定事项映射
     * @return 当前状态、有效版本和公开补正说明
     */
    private ResourceState loadCurrentResource(CaseRow reviewCase) {
        if (reviewCase.type() == ReviewCaseType.ORGANIZATION) {
            return jdbc.query("""
                    SELECT status, application_version, review_reason
                    FROM business_organization WHERE id = ? AND tenant_id = ?
                    """, result -> result.next() ? new ResourceState(result.getString("status"),
                    result.getLong("application_version"), result.getString("review_reason")) : null,
                    reviewCase.resourceId(), reviewCase.tenantId());
        }
        return jdbc.query("""
                SELECT status, current_revision, review_reason
                FROM business_demand WHERE id = ? AND tenant_id = ?
                """, result -> result.next() ? new ResourceState(result.getString("status"),
                result.getLong("current_revision"), result.getString("review_reason")) : null,
                reviewCase.resourceId(), reviewCase.tenantId());
    }

    /**
     * 从领域修订表和版本绑定的审核决定读取历史，不重建或覆盖业务快照。
     * @param reviewCase 稳定事项映射
     * @return 该资源的版本摘要
     */
    private List<Version> loadVersions(CaseRow reviewCase) {
        if (reviewCase.type() == ReviewCaseType.ORGANIZATION) {
            return jdbc.query("""
                    SELECT r.revision, r.created_at,
                        (SELECT MIN(a.created_at) FROM foundation_audit a
                         WHERE a.review_case_id = ? AND a.resource_version = r.revision
                           AND a.action = 'organization:submit' AND a.outcome = 'SUCCESS') AS submitted_at,
                        (SELECT d.id FROM business_review_decision d
                         WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'ORGANIZATION'
                           AND d.resource_id = r.organization_id AND d.resource_version = r.revision
                         ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision_id,
                        (SELECT d.decision FROM business_review_decision d
                         WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'ORGANIZATION'
                           AND d.resource_id = r.organization_id AND d.resource_version = r.revision
                         ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision,
                        (SELECT d.created_at FROM business_review_decision d
                         WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'ORGANIZATION'
                           AND d.resource_id = r.organization_id AND d.resource_version = r.revision
                         ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision_at,
                        (SELECT d.reason FROM business_review_decision d
                         WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'ORGANIZATION'
                           AND d.resource_id = r.organization_id AND d.resource_version = r.revision
                         ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS public_reason
                    FROM business_organization_revision r
                    WHERE r.organization_id = ? AND r.tenant_id = ?
                      AND (? IS NULL OR r.revision >= ?)
                    ORDER BY r.revision
                        """, (result, row) -> mapVersion(result, reviewCase), Long.toString(reviewCase.id()),
                    reviewCase.resourceId(), reviewCase.tenantId(), reviewCase.initialVersion(),
                    reviewCase.initialVersion());
        }
        return jdbc.query("""
                SELECT r.revision, r.created_at,
                    (SELECT MIN(a.created_at) FROM foundation_audit a
                     WHERE a.review_case_id = ? AND a.resource_version = r.revision AND a.outcome = 'SUCCESS'
                       AND (a.action = 'demand:submit'
                            OR (a.action = 'demand:update' AND a.reason_code = 'RESUBMITTED_FOR_REVIEW')))
                        AS submitted_at,
                    (SELECT d.id FROM business_review_decision d
                     WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'DEMAND'
                       AND d.resource_id = r.demand_id AND d.resource_version = r.revision
                     ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision_id,
                    (SELECT d.decision FROM business_review_decision d
                     WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'DEMAND'
                       AND d.resource_id = r.demand_id AND d.resource_version = r.revision
                     ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision,
                    (SELECT d.created_at FROM business_review_decision d
                     WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'DEMAND'
                       AND d.resource_id = r.demand_id AND d.resource_version = r.revision
                     ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS decision_at,
                    (SELECT d.reason FROM business_review_decision d
                     WHERE d.tenant_id = r.tenant_id AND d.resource_type = 'DEMAND'
                       AND d.resource_id = r.demand_id AND d.resource_version = r.revision
                     ORDER BY d.created_at DESC, d.id DESC LIMIT 1) AS public_reason
                FROM business_demand_revision r
                WHERE r.demand_id = ? AND r.tenant_id = ?
                  AND (? IS NULL OR r.revision >= ?)
                ORDER BY r.revision
                """, (result, row) -> mapVersion(result, reviewCase), Long.toString(reviewCase.id()),
                reviewCase.resourceId(), reviewCase.tenantId(), reviewCase.initialVersion(),
                reviewCase.initialVersion());
    }

    /** 读取事项版本绑定的只追加补正清单，避免将其他版本要求混入当前审核。 */
    private List<CorrectionItem> loadCorrectionItems(CaseRow reviewCase, long resourceVersion) {
        return jdbc.query("""
                SELECT id, item_code, title, description, applicant_action, created_at
                FROM platform_review_correction_item
                WHERE tenant_id = ? AND review_case_id = ? AND resource_type = ?
                  AND resource_id = ? AND resource_version = ?
                ORDER BY id
                """, (result, row) -> new CorrectionItem(Long.toString(result.getLong("id")),
                result.getString("item_code"), result.getString("title"), result.getString("description"),
                result.getString("applicant_action"), result.getTimestamp("created_at").toInstant()),
                reviewCase.tenantId(), reviewCase.id(), reviewCase.type().name(), reviewCase.resourceId(),
                resourceVersion);
    }

    /**
     * 按申请方字段白名单读取事项审计事件；内部视图保留失败、拒绝及岗位信息。
     * @param reviewCase 稳定事项映射
     * @param internalView 是否读取内部完整事件范围
     * @return 按发生时间及审计编号排序的事件摘要
     */
    private List<Event> loadEvents(CaseRow reviewCase, boolean internalView) {
        if (internalView) {
            return jdbc.query("""
                    SELECT id, action, resource_version, outcome, actor_id, actor_role, reason_code, created_at
                    FROM foundation_audit WHERE review_case_id = ? ORDER BY created_at, id
                    """, (result, row) -> mapEvent(result, true), Long.toString(reviewCase.id()));
        }
        return jdbc.query("""
                SELECT id, action, resource_version, outcome, actor_id, actor_role, reason_code, created_at
                FROM foundation_audit
                WHERE review_case_id = ? AND outcome = 'SUCCESS' AND action IN (%s)
                ORDER BY created_at, id
                """.formatted(APPLICANT_ACTIONS_SQL), (result, row) -> mapEvent(result, false),
                Long.toString(reviewCase.id()));
    }

    /**
     * 为申请方的版本编辑、提交和审核动作写入公开字段白名单后的事件摘要。
     * @param result SQL 当前行
     * @return 只保留动作和时间的申请方事件
     * @throws SQLException 数据库列读取失败
     */
    private Event mapEvent(ResultSet result, boolean internalView) throws SQLException {
        return new Event(Long.toString(result.getLong("id")), result.getString("action"),
                result.getObject("resource_version", Long.class), result.getString("outcome"),
                internalView ? stringValue(result, "actor_id") : null,
                internalView ? result.getString("actor_role") : null,
                internalView ? result.getString("reason_code") : null,
                result.getTimestamp("created_at").toInstant());
    }

    /**
     * 将数据库审核版本映射到仅含版本号、时间及申请方可见决定的 DTO。
     * @param result SQL 当前行
     * @return 业务版本摘要
     * @throws SQLException 数据库列读取失败
     */
    private Version mapVersion(ResultSet result, CaseRow reviewCase) throws SQLException {
        long version = result.getLong("revision");
        return new Version(result.getLong("revision"), result.getTimestamp("created_at").toInstant(),
                instantOrNull(result, "submitted_at"), stringValue(result, "decision_id"),
                result.getString("decision"),
                instantOrNull(result, "decision_at"), result.getString("public_reason"),
                loadCorrectionItems(reviewCase, version));
    }

    /**
     * 按精确资源类型、归属组织和资源编号读取事项映射，避免跨组织串案。
     * @param type 组织或需求资源类型
     * @param resourceId 业务资源编号
     * @param tenantId 资源归属组织编号
     * @return 稳定事项映射
     * @throws BusinessException 资源尚无已提交事项
     */
    private CaseRow requireCase(ReviewCaseType type, long resourceId, long tenantId) {
        CaseRow row = jdbc.query("""
                SELECT id, tenant_id, resource_type, resource_id, initial_version, history_coverage
                FROM platform_review_case
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?
                """, result -> result.next() ? mapCase(result) : null, tenantId, type.name(), resourceId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /**
     * 记录每次事项历史读取并限制在指定事项与用途，审计存储失败时本次读取失败。
     * @param reviewCase 被读取事项
     * @param purposeCode 申请方、审核或监察的稳定用途码
     */
    private void recordRead(CaseRow reviewCase, String purposeCode) {
        ResourceState state = loadCurrentResource(reviewCase);
        AuditRecorder.AuditDetails details = new AuditRecorder.AuditDetails(Long.toString(reviewCase.id()),
                null, null, null, state.currentVersion(), null, "HISTORY_READ", purposeCode);
        audit.record("platform:review_case:history:read", "review_case", Long.toString(reviewCase.id()),
                "SUCCESS", details);
    }

    /**
     * 查询当前事项编号，草稿及尚未首次提交的申请返回空值。
     * @param type 资源类型
     * @param resourceId 业务资源编号
     * @param tenantId 资源归属组织编号
     * @return 稳定事项编号或空值
     */
    private Long findCaseId(ReviewCaseType type, long resourceId, long tenantId) {
        return jdbc.query("""
                SELECT id FROM platform_review_case
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?
                """, result -> result.next() ? result.getLong("id") : null, tenantId, type.name(), resourceId);
    }

    /**
     * 确认事项首次创建时资源已处于提交审核状态且版本与领域当前值一致。
     * @param type 资源类型
     * @param resourceId 资源编号
     * @param tenantId 所属组织
     * @param version 提交版本
     * @return 当前资源版本符合提交条件时为 true
     */
    private boolean isCurrentSubmittedVersion(ReviewCaseType type, long resourceId, long tenantId, long version) {
        if (type == ReviewCaseType.ORGANIZATION) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM business_organization
                        WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW'
                          AND application_version = ?)
                    """, Boolean.class, resourceId, tenantId, version));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM business_demand
                    WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW' AND current_revision = ?)
                """, Boolean.class, resourceId, tenantId, version));
    }

    /**
     * 校验所有业务资源标识均为正数，拒绝把无效关联写入事项表。
     * @param values 资源、组织、版本和账号编号
     * @throws IllegalArgumentException 任一编号不为正数
     */
    private void validatePositive(long... values) {
        for (long value : values) {
            if (value <= 0) {
                throw new IllegalArgumentException("Review case identifiers and versions must be positive");
            }
        }
    }

    /**
     * 映射审核事项数据库行。
     * @param result SQL 当前行
     * @return 审核事项映射
     * @throws SQLException 数据库列读取失败
     */
    private CaseRow mapCase(ResultSet result) throws SQLException {
        return new CaseRow(result.getLong("id"), result.getLong("tenant_id"),
                ReviewCaseType.valueOf(result.getString("resource_type")), result.getLong("resource_id"),
                result.getObject("initial_version", Long.class), result.getString("history_coverage"));
    }

    /**
     * 安全转换可空 bigint 操作者编号，避免把 NULL 错映射成账号零。
     * @param result SQL 当前行
     * @param column 数据库列名
     * @return 十进制账号编号或空值
     * @throws SQLException 数据库列读取失败
     */
    private String stringValue(ResultSet result, String column) throws SQLException {
        Long value = result.getObject(column, Long.class);
        return value == null ? null : Long.toString(value);
    }

    /**
     * 将可空时间戳安全转换为 UTC 时间点。
     * @param result SQL 当前行
     * @param column 数据库列名
     * @return UTC 时间点或空值
     * @throws SQLException 数据库列读取失败
     */
    private Instant instantOrNull(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 表示当前组织申请或需求状态，不独立保存到历史表。
     * @param status 业务状态代码
     * @param currentVersion 当前业务版本
     * @param reviewReason 当前公开补正说明
     */
    private record ResourceState(String status, long currentVersion, String reviewReason) {
    }

    /**
     * 表示稳定事项编号与资源归属，不包括任何申请材料正文。
     * @param id 稳定事项编号
     * @param tenantId 资源归属组织编号
     * @param type 资源类型
     * @param resourceId 业务资源编号
     * @param historyCoverage 完整或迁移前存在审计缺口
     */
    private record CaseRow(long id, long tenantId, ReviewCaseType type, long resourceId,
                           Long initialVersion, String historyCoverage) {
    }

    /** 已通过资源领域校验、等待持久化的申请方补正事项。 */
    public record CorrectionItemInput(String itemCode, String title, String description, String applicantAction) {
        /**
         * @param itemCode 审核版本内唯一的问题代码
         * @param title 问题标题
         * @param description 问题说明
         * @param applicantAction 申请方应执行的补正动作
         */
    }
}
