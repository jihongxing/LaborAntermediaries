package com.company.labor.modules.platform;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.modules.auth.PlatformAuthorizationService;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos.View;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** 提供按版本绑定的岗位任务、原子认领、回避、异常恢复和独立申诉复核。 */
@Service
public class PlatformTaskService {
    /** 认领幂等键使用标识字符，避免正文进入索引或审计数据。 */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[a-zA-Z0-9:._-]{1,128}");
    /** 任务与复核业务数据访问入口。 */
    private final JdbcTemplate jdbc;
    /** 所有任务编号共用基础设施序列。 */
    private final SequenceIds ids;
    /** 根据实时账号、会话和岗位授权执行服务端权限校验。 */
    private final PlatformAuthorizationService authorization;
    /** 成功事务及拒绝失败均写入同一追加式审计契约。 */
    private final AuditRecorder audit;
    /** 认领成功后提供同一事项的内部版本历史。 */
    private final ReviewCaseHistoryService reviewHistory;

    /**
     * 装配任务、授权、审计和事项历史服务。
     * @param jdbc 任务与复核数据访问入口
     * @param ids 数据库序列编号服务
     * @param authorization 平台实时权限服务
     * @param audit 追加式审计写入服务
     * @param reviewHistory 版本化审核事项历史服务
     */
    public PlatformTaskService(JdbcTemplate jdbc, SequenceIds ids, PlatformAuthorizationService authorization,
                               AuditRecorder audit, ReviewCaseHistoryService reviewHistory) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.authorization = authorization;
        this.audit = audit;
        this.reviewHistory = reviewHistory;
    }

    /**
     * 在提交业务事务内创建对应的组织或需求版本任务，并关闭已被新版本替代的旧任务。
     * @param reviewCaseId 稳定事项编号
     * @param caseType 组织或需求资源类型
     * @param resourceId 组织或需求编号
     * @param tenantId 资源所属组织
     * @param resourceVersion 本次提交审核的当前版本
     * @return 新建或已存在的岗位任务编号
     * @throws BusinessException 事项、资源状态或版本与当前提交不一致
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long openReviewTaskOnSubmission(long reviewCaseId, ReviewCaseType caseType, long resourceId,
                                           long tenantId, long resourceVersion) {
        validatePositive(reviewCaseId, resourceId, tenantId, resourceVersion);
        PlatformTaskType taskType = PlatformTaskType.forReviewCase(caseType);
        if (!isCurrentSubmission(caseType, resourceId, tenantId, resourceVersion)) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        supersedeOlderTasks(reviewCaseId, resourceVersion);
        Long existing = jdbc.query("""
                SELECT id FROM platform_review_task
                WHERE review_case_id = ? AND task_type = ? AND resource_version = ?
                """, result -> result.next() ? result.getLong("id") : null,
                reviewCaseId, taskType.name(), resourceVersion);
        if (existing != null) {
            return existing;
        }
        long taskId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_review_task
                    (id, tenant_id, review_case_id, task_type, resource_type, resource_id,
                     resource_version, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, taskId, tenantId, reviewCaseId, taskType.name(), caseType.name(), resourceId, resourceVersion);
        audit.record("platform:task:created", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(reviewCaseId, null, taskId, resourceVersion, "SUBMISSION_TASK_CREATED"));
        return taskId;
    }

    /**
     * 读取当前岗位队列的最小摘要；不包含申请材料、审核正文或其他认领人的身份。
     * @param taskType 请求的岗位任务种类
     * @return 当前任务队列摘要，最多返回最近 100 项
     * @throws BusinessException 当前平台账号无对应实时岗位权限
     */
    @Transactional
    public List<QueueItem> queue(PlatformTaskType taskType) {
        authorization.requirePermission(taskType.permission());
        ExecutionContext.Identity identity = ExecutionContext.require();
        List<QueueItem> items = jdbc.query("""
                SELECT t.id, t.task_type, t.review_case_id, t.appeal_case_id, t.resource_type,
                       t.resource_version, t.status, t.created_at,
                       EXISTS (SELECT 1 FROM platform_task_claim c
                           WHERE c.task_id = t.id AND c.status = 'ACTIVE' AND c.actor_id = ?) AS claimed_by_me
                FROM platform_review_task t
                WHERE t.task_type = ? AND t.status IN ('PENDING', 'CLAIMED')
                ORDER BY t.created_at, t.id
                LIMIT 100
                """, (result, row) -> new QueueItem(Long.toString(result.getLong("id")),
                result.getString("task_type"), Long.toString(result.getLong("review_case_id")),
                stringValue(result, "appeal_case_id"), result.getString("resource_type"),
                Long.toString(result.getLong("resource_version")), result.getString("status"),
                result.getBoolean("claimed_by_me"), result.getTimestamp("created_at").toInstant()),
                identity.actorId(), taskType.name());
        audit.record("platform:task:queue:read", "platform_task_queue", taskType.name(), "SUCCESS",
                AuditRecorder.AuditDetails.empty());
        return List.copyOf(items);
    }

    /**
     * 原子认领本岗位任务；数据库任务锁和有效认领唯一索引裁定并发，幂等请求返回原认领。
     * @param taskId 岗位任务编号
     * @param idempotencyKey 本次认领请求的幂等键
     * @return 稳定认领结果；同一有效认领可以由不同请求键复用
     * @throws BusinessException 无权限、冲突回避、任务已占用或版本已过期
     */
    @Transactional
    public ClaimResult claim(long taskId, String idempotencyKey) {
        requirePositive(taskId);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        TaskRow task = requireTask(taskId, true);
        requireTaskPermission(task);
        ensureCurrentVersion(task);
        ensureNoConflict(task, "TASK_CLAIM_CONFLICT");
        long actorId = ExecutionContext.require().actorId();
        ClaimRequest priorRequest = jdbc.query("""
                SELECT r.claim_id, c.status FROM platform_task_claim_request r
                JOIN platform_task_claim c ON c.id = r.claim_id AND c.task_id = r.task_id
                WHERE r.task_id = ? AND r.actor_id = ? AND r.idempotency_key = ?
                """, result -> result.next()
                ? new ClaimRequest(result.getLong("claim_id"), result.getString("status")) : null,
                taskId, actorId, idempotencyKey);
        if (priorRequest != null) {
            recordClaimRetry(task, priorRequest.claimId());
            return claimResult(taskId, priorRequest.claimId(), priorRequest.status());
        }
        ClaimRequest active = activeClaim(taskId, true);
        if (active != null) {
            if (active.actorId() == actorId) {
                insertClaimRequest(taskId, actorId, idempotencyKey, active.claimId());
                recordClaimRetry(task, active.claimId());
                return claimResult(taskId, active.claimId(), "ACTIVE");
            }
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_ALREADY_CLAIMED",
                    PlatformError.DATA_CONFLICT);
        }
        if (!"PENDING".equals(task.status())) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_NOT_PENDING", PlatformError.DATA_CONFLICT);
        }
        long claimId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_task_claim (id, task_id, actor_id, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, claimId, taskId, actorId);
        insertClaimRequest(taskId, actorId, idempotencyKey, claimId);
        updateTaskStatus(task, "CLAIMED");
        updateAppealStatus(task, "IN_REVIEW");
        audit.record("platform:task:claimed", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "CLAIMED"));
        return claimResult(taskId, claimId, "ACTIVE");
    }

    /**
     * 读取已认领任务的有限详情及内部历史；不开放 G6 的材料或敏感附件内容。
     * @param taskId 任务编号
     * @return 任务标识、事项版本、必要复核摘要及处理历史
     * @throws BusinessException 当前操作者未认领该任务、冲突或版本已失效
     */
    @Transactional
    public TaskDetails details(long taskId) {
        TaskRow task = requireTask(taskId, false);
        requireTaskPermission(task);
        requireOwnedClaim(task);
        ensureCurrentVersion(task);
        ensureNoConflict(task, "TASK_DETAIL_CONFLICT");
        AppealSummary appeal = task.appealCaseId() == null ? null : loadAppealSummary(task);
        View history = reviewHistory.claimedTaskHistory(taskId);
        audit.record("platform:task:detail:read", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "TASK_DETAIL_READ"));
        return new TaskDetails(task.publicView(), history, appeal);
    }

    /**
     * 在读取材料前重新校验平台岗位、对象版本、有效认领和回避状态。
     * @param taskId 材料所属的岗位任务
     * @return 服务端确认的不可变证据访问范围
     * @throws BusinessException 无岗位权限、任务不由本人认领、版本失效或存在回避冲突
     */
    @Transactional
    public EvidenceTaskScope requireEvidenceTask(long taskId) {
        TaskRow task = requireTask(taskId, true);
        requireTaskPermission(task);
        requireOwnedClaim(task);
        ensureCurrentVersion(task);
        ensureNoConflict(task, "EVIDENCE_TASK_CONFLICT");
        return new EvidenceTaskScope(task.taskId(), task.tenantId(), task.reviewCaseId(), task.resourceType(),
                task.resourceId(), task.resourceVersion(), task.taskType().name());
    }

    /**
     * 由本人说明理由后释放任务，任务回到原岗位待认领队列。
     * @param taskId 本人当前认领的任务
     * @param reason 释放理由，不写入通用审计摘要
     * @return 释放后的稳定任务编号
     * @throws BusinessException 任务不是本人有效认领或当前版本已失效
     */
    @Transactional
    public String release(long taskId, String reason) {
        TaskRow task = requireTask(taskId, true);
        requireTaskPermission(task);
        ensureCurrentVersion(task);
        ClaimRequest claim = requireOwnedClaim(task);
        String normalizedReason = requiredReason(reason);
        closeClaim(task, claim.claimId(), "RELEASED", normalizedReason);
        updateTaskStatus(task, "PENDING");
        updateAppealStatus(task, "PENDING");
        audit.record("platform:task:released", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "OWNER_RELEASED"));
        return Long.toString(taskId);
    }

    /**
     * 记录审核员针对自己的回避申报；如本人已占用任务，立即撤销访问并退回岗位队列。
     * @param taskId 涉及的岗位任务
     * @param reason 自我申报原因
     * @return 稳定任务编号
     * @throws BusinessException 无对应岗位权限或任务版本已失效
     */
    @Transactional
    public String declareRecusal(long taskId, String reason) {
        TaskRow task = requireTask(taskId, true);
        requireTaskPermission(task);
        ensureCurrentVersion(task);
        ensureOpenTask(task);
        String normalizedReason = requiredReason(reason);
        long actorId = ExecutionContext.require().actorId();
        jdbc.update("""
                INSERT INTO platform_task_conflict
                    (id, task_id, subject_account_id, reported_by, conflict_type, reason)
                VALUES (?, ?, ?, ?, 'SELF_DECLARED', ?)
                ON CONFLICT (task_id, subject_account_id, conflict_type) DO NOTHING
                """, ids.nextId(), taskId, actorId, actorId, normalizedReason);
        ClaimRequest active = activeClaim(taskId, true);
        if (active != null && active.actorId() == actorId) {
            closeClaim(task, active.claimId(), "RECUSED", normalizedReason);
            updateTaskStatus(task, "PENDING");
            updateAppealStatus(task, "PENDING");
        }
        audit.record("platform:task:recused", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "SELF_DECLARED"));
        return Long.toString(taskId);
    }

    /**
     * 返回仅含任务编号、岗位和占用时间的异常恢复队列，不提供事项详情或材料读取能力。
     * @return 当前有效占用任务的最小恢复摘要
     * @throws BusinessException 当前岗位没有独立任务恢复权限
     */
    @Transactional
    public List<RecoveryItem> recoveryQueue() {
        authorization.requirePermission("platform:tasks:reopen");
        List<RecoveryItem> items = jdbc.query("""
                SELECT t.id, t.task_type, t.status, c.claimed_at
                FROM platform_review_task t
                JOIN platform_task_claim c ON c.task_id = t.id AND c.status = 'ACTIVE'
                WHERE t.status = 'CLAIMED'
                ORDER BY c.claimed_at, t.id
                LIMIT 100
                """, (result, row) -> new RecoveryItem(Long.toString(result.getLong("id")),
                result.getString("task_type"), result.getString("status"),
                result.getTimestamp("claimed_at").toInstant()));
        audit.record("platform:task:queue:read", "platform_task_queue", "RECOVERY", "SUCCESS",
                AuditRecorder.AuditDetails.empty());
        return List.copyOf(items);
    }

    /**
     * 使用独立恢复权限解除任务占用并退回原岗位队列，不读取或处理事项内容。
     * @param taskId 异常占用任务编号
     * @param reason 恢复原因
     * @return 已退回队列的任务编号
     * @throws BusinessException 无独立恢复权限、任务未占用或版本已失效
     */
    @Transactional
    public String recover(long taskId, String reason) {
        authorization.requirePermission("platform:tasks:reopen");
        TaskRow task = requireTask(taskId, true);
        ensureCurrentVersion(task);
        ClaimRequest claim = activeClaim(taskId, true);
        if (claim == null || !"CLAIMED".equals(task.status())) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_NOT_CLAIMED", PlatformError.DATA_CONFLICT);
        }
        String normalizedReason = requiredReason(reason);
        closeClaim(task, claim.claimId(), "RECOVERED", normalizedReason);
        updateTaskStatus(task, "PENDING");
        updateAppealStatus(task, "PENDING");
        // 通知必须与解除认领一同提交；不把自由文本理由复制到普通通知。
        long noticeId = ids.nextId();
        int notices = jdbc.update("""
                INSERT INTO platform_task_recovery_notice
                    (id, tenant_id, task_id, claim_id, recipient_account_id)
                SELECT ?, ?, ?, c.id, c.actor_id
                FROM platform_task_claim c
                WHERE c.id = ? AND c.task_id = ? AND c.status = 'RECOVERED'
                """, noticeId, task.tenantId(), taskId, claim.claimId(), taskId);
        if (notices != 1) {
            throw new IllegalStateException("Recovered claim notice was not persisted");
        }
        audit.record("platform:task:recovery:notice:created", "platform_task_recovery_notice",
                Long.toString(noticeId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(),
                        "ORIGINAL_CLAIMANT_NOTIFIED"));
        audit.record("platform:task:recovered", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "RECOVERY_RETURNED"));
        return Long.toString(taskId);
    }

    /**
     * 向已认证账号返回其原认领被异常解除的站内通知；岗位撤销后仍可通过个人账号查看。
     * @return 最多 100 条仅含任务类型、编号和时间的本人通知
     * @throws BusinessException 当前请求未经过账号认证
     */
    @Transactional
    public List<RecoveryNotice> recoveryNotices() {
        long actorId = ExecutionContext.require().actorId();
        List<RecoveryNotice> notices = jdbc.query("""
                SELECT n.id, n.task_id, t.task_type, c.claimed_at, n.created_at
                FROM platform_task_recovery_notice n
                JOIN platform_review_task t ON t.id = n.task_id AND t.tenant_id = n.tenant_id
                JOIN platform_task_claim c ON c.id = n.claim_id AND c.task_id = n.task_id
                WHERE n.recipient_account_id = ?
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT 100
                """, (result, row) -> new RecoveryNotice(Long.toString(result.getLong("id")),
                Long.toString(result.getLong("task_id")), result.getString("task_type"),
                result.getTimestamp("claimed_at").toInstant(),
                result.getTimestamp("created_at").toInstant()), actorId);
        audit.record("platform:task:recovery:notice:read", "platform_task_recovery_notice", "SELF", "SUCCESS",
                AuditRecorder.AuditDetails.empty());
        return List.copyOf(notices);
    }

    /**
     * 由实际申请组织一方请求当前审核员回避；有效请求会立刻关闭该审核员的认领。
     * @param taskId 被请求回避的任务
     * @param reason 当事方说明的回避理由
     * @return 已记录请求的任务编号
     * @throws BusinessException 请求人不是事项一方或任务当前无人认领
     */
    @Transactional
    public String requestPartyRecusal(long taskId, String reason) {
        TaskRow task = requireTask(taskId, true);
        ensureOpenTask(task);
        requireApplicantParty(task);
        ClaimRequest claim = activeClaim(taskId, true);
        if (claim == null || !"CLAIMED".equals(task.status())) {
            rejectTaskAction(task, "platform:task:recusal:requested", "TASK_NOT_CLAIMED", PlatformError.DATA_CONFLICT);
        }
        String normalizedReason = requiredReason(reason);
        long reporterId = ExecutionContext.require().actorId();
        jdbc.update("""
                INSERT INTO platform_task_conflict
                    (id, task_id, subject_account_id, reported_by, conflict_type, reason)
                VALUES (?, ?, ?, ?, 'PARTY_REQUESTED', ?)
                ON CONFLICT (task_id, subject_account_id, conflict_type) DO NOTHING
                """, ids.nextId(), taskId, claim.actorId(), reporterId, normalizedReason);
        closeClaim(task, claim.claimId(), "RECUSED", normalizedReason);
        updateTaskStatus(task, "PENDING");
        updateAppealStatus(task, "PENDING");
        audit.record("platform:task:recusal:requested", "platform_review_task", Long.toString(taskId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(), "PARTY_REQUESTED"));
        return Long.toString(taskId);
    }

    /**
     * 申请方针对仍对应当前业务版本的原审核决定建立独立复核事项和岗位任务。
     * @param originalDecisionId 原审核决定编号
     * @param reason 申请方提交的复核理由
     * @return 新复核事项及任务编号
     * @throws BusinessException 申请人不是该事项一方、决定已过期或已有待处理复核
     */
    @Transactional
    public AppealCreated createAppeal(long originalDecisionId, String reason) {
        requirePositive(originalDecisionId);
        String normalizedReason = requiredReason(reason);
        DecisionRow decision = loadDecision(originalDecisionId);
        if (decision == null) {
            audit.recordSecurityEvent("platform:appeal:create:denied", "business_review_decision",
                    Long.toString(originalDecisionId), "DENIED", "DECISION_NOT_AVAILABLE");
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        requireApplicantParty(decision);
        if (currentVersion(decision.resourceType(), decision.resourceId(), decision.tenantId())
                != decision.resourceVersion()) {
            rejectAppeal(decision, "APPEAL_VERSION_SUPERSEDED", PlatformError.VERSION_CONFLICT);
        }
        long appealId = ids.nextId();
        int inserted = jdbc.update("""
                INSERT INTO platform_appeal_case
                    (id, review_case_id, tenant_id, original_decision_id, resource_version,
                     requested_by, request_reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT DO NOTHING
                """, appealId, decision.reviewCaseId(), decision.tenantId(), decision.id(),
                decision.resourceVersion(), ExecutionContext.require().actorId(), normalizedReason);
        if (inserted != 1) {
            audit.record("platform:appeal:create:conflict", "business_review_decision",
                    Long.toString(originalDecisionId), "CONFLICT",
                    details(decision.reviewCaseId(), null, null, decision.resourceVersion(), "OPEN_APPEAL_EXISTS"));
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        long taskId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_review_task
                    (id, tenant_id, review_case_id, appeal_case_id, task_type, resource_type,
                     resource_id, resource_version, status)
                VALUES (?, ?, ?, ?, 'APPEAL_REVIEW', ?, ?, ?, 'PENDING')
                """, taskId, decision.tenantId(), decision.reviewCaseId(), appealId, decision.resourceType(),
                decision.resourceId(), decision.resourceVersion());
        audit.record("platform:appeal:created", "platform_appeal_case", Long.toString(appealId), "SUCCESS",
                details(decision.reviewCaseId(), appealId, taskId, decision.resourceVersion(), "APPEAL_SUBMITTED"));
        return new AppealCreated(Long.toString(appealId), Long.toString(taskId), decision.resourceVersion(), "PENDING");
    }

    /**
     * 记录独立复核意见并结束复核任务；结果不改写原审核决定或业务状态。
     * @param taskId 已认领的异议复核任务编号
     * @param outcome 经复核形成的意见类型
     * @param reason 复核理由
     * @return 已追加记录的复核结果
     * @throws BusinessException 操作者参与原处理、存在冲突或任务版本失效
     */
    @Transactional
    public AppealReviewResult completeAppealReview(long taskId, AppealOutcome outcome, String reason) {
        TaskRow task = requireTask(taskId, true);
        requireTaskPermission(task);
        if (task.taskType() != PlatformTaskType.APPEAL_REVIEW || task.appealCaseId() == null) {
            rejectTaskAction(task, "platform:appeal:review:denied", "APPEAL_TASK_REQUIRED", PlatformError.ACCESS_DENIED);
        }
        ensureCurrentVersion(task);
        ensureNoConflict(task, "APPEAL_REVIEW_CONFLICT");
        ClaimRequest claim = requireOwnedClaim(task);
        String normalizedReason = requiredReason(reason);
        long resultId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_appeal_review_result (id, appeal_case_id, reviewer_id, outcome, reason)
                VALUES (?, ?, ?, ?, ?)
                """, resultId, task.appealCaseId(), ExecutionContext.require().actorId(), outcome.name(),
                normalizedReason);
        int appealUpdated = jdbc.update("""
                UPDATE platform_appeal_case SET status = 'REVIEWED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'IN_REVIEW'
                """, task.appealCaseId());
        if (appealUpdated != 1) {
            rejectTaskAction(task, "platform:appeal:review:denied", "APPEAL_STATE_CHANGED",
                    PlatformError.DATA_CONFLICT);
        }
        closeClaim(task, claim.claimId(), "COMPLETED", "APPEAL_REVIEW_COMPLETED");
        updateTaskStatus(task, "COMPLETED");
        audit.record("platform:appeal:reviewed", "platform_appeal_case", Long.toString(task.appealCaseId()),
                "SUCCESS", details(task.reviewCaseId(), task.appealCaseId(), taskId, task.resourceVersion(),
                        outcome.name()));
        Instant reviewedAt = jdbc.queryForObject("""
                SELECT reviewed_at FROM platform_appeal_review_result WHERE id = ?
                """, (result, rowNumber) -> result.getTimestamp(1).toInstant(), resultId);
        return new AppealReviewResult(Long.toString(resultId), Long.toString(task.appealCaseId()),
                outcome.name(), ExecutionContext.require().actorId(), normalizedReason, reviewedAt);
    }

    /**
     * 要求审核服务使用当前账号对当前事项版本持有的有效任务，阻断旧待审接口绕过认领。
     * @param caseType 组织或需求资源类型
     * @param resourceId 业务对象编号
     * @param resourceVersion 业务对象当前审核版本
     * @return 当前账号有效认领的任务引用
     * @throws BusinessException 无有效认领、岗位撤销、回避冲突或版本已变更
     */
    @Transactional
    public TaskRef requireClaimedReviewTask(ReviewCaseType caseType, long resourceId, long resourceVersion) {
        PlatformTaskType type = PlatformTaskType.forReviewCase(caseType);
        authorization.requirePermission(type.permission());
        ExecutionContext.Identity identity = ExecutionContext.require();
        TaskRow task = jdbc.query("""
                SELECT t.id, t.tenant_id, t.review_case_id, t.appeal_case_id, t.task_type,
                       t.resource_type, t.resource_id, t.resource_version, t.status, t.created_at
                FROM platform_review_task t
                JOIN platform_task_claim c ON c.task_id = t.id AND c.status = 'ACTIVE'
                WHERE t.task_type = ? AND t.resource_type = ? AND t.resource_id = ?
                  AND t.resource_version = ? AND t.status = 'CLAIMED' AND c.actor_id = ?
                FOR UPDATE OF t, c
                """, result -> result.next() ? mapTask(result) : null, type.name(), caseType.name(),
                resourceId, resourceVersion, identity.actorId());
        if (task == null) {
            audit.recordSecurityEvent("platform:task:review:denied", "platform_review_task",
                    Long.toString(resourceId), "DENIED", "ACTIVE_CLAIM_REQUIRED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        ensureCurrentVersion(task);
        ensureNoConflict(task, "REVIEW_TASK_CONFLICT");
        return task.reference();
    }

    /**
     * 在原领域审核状态与决定事务内关闭当前认领，令任务历史和业务决定原子一致。
     * @param taskRef 领域服务先行核验得到的认领任务
     * @param decision 审核结论代码
     * @throws BusinessException 认领在同一请求内失效或审计不能可靠写入
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeReviewTask(TaskRef taskRef, String decision) {
        TaskRow task = requireTask(taskRef.taskId(), true);
        requireTaskPermission(task);
        if (task.reviewCaseId() != taskRef.reviewCaseId() || task.resourceVersion() != taskRef.resourceVersion()
                || task.taskType() != taskRef.taskType()) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_REFERENCE_MISMATCH",
                    PlatformError.VERSION_CONFLICT);
        }
        if (currentVersion(task.resourceType(), task.resourceId(), task.tenantId()) != task.resourceVersion()) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_VERSION_OR_STATE_STALE",
                    PlatformError.VERSION_CONFLICT);
        }
        ensureNoConflict(task, "REVIEW_TASK_CONFLICT");
        ClaimRequest claim = requireOwnedClaim(task);
        closeClaim(task, claim.claimId(), "COMPLETED", decision);
        updateTaskStatus(task, "COMPLETED");
        audit.record("platform:task:completed", "platform_review_task", Long.toString(task.taskId()), "SUCCESS",
                details(task.reviewCaseId(), null, task.taskId(), task.resourceVersion(), decision));
    }

    /**
     * 载入审核员已获授权的原决定说明，不包含材料正文或个人联系方式。
     * @param task 已认领的复核任务
     * @return 原审核决定与申请理由摘要
     */
    private AppealSummary loadAppealSummary(TaskRow task) {
        return jdbc.query("""
                SELECT a.id, a.original_decision_id, a.requested_by, a.request_reason,
                       d.decision, d.to_status, d.reviewer_id, d.created_at
                FROM platform_appeal_case a
                JOIN business_review_decision d ON d.id = a.original_decision_id
                WHERE a.id = ? AND a.review_case_id = ? AND a.resource_version = ?
                """, result -> result.next() ? new AppealSummary(Long.toString(result.getLong("id")),
                Long.toString(result.getLong("original_decision_id")), Long.toString(result.getLong("requested_by")),
                result.getString("request_reason"), result.getString("decision"), result.getString("to_status"),
                Long.toString(result.getLong("reviewer_id")), result.getTimestamp("created_at").toInstant()) : null,
                task.appealCaseId(), task.reviewCaseId(), task.resourceVersion());
    }

    /**
     * 按事项类型校验任务版本仍指向当前可审核对象；独立复核允许原业务结论已结束。
     * @param task 待检查任务
     * @throws BusinessException 资源不存在、版本过期或普通审核状态已关闭
     */
    private void ensureCurrentVersion(TaskRow task) {
        String status;
        Long version;
        if ("ORGANIZATION".equals(task.resourceType())) {
            ResourceState state = jdbc.query("""
                    SELECT status, application_version AS current_version
                    FROM business_organization WHERE id = ? AND tenant_id = ?
                    """, result -> result.next() ? new ResourceState(result.getString("status"),
                    result.getLong("current_version")) : null, task.resourceId(), task.tenantId());
            status = state == null ? null : state.status();
            version = state == null ? null : state.version();
        } else {
            ResourceState state = jdbc.query("""
                    SELECT status, current_revision AS current_version
                    FROM business_demand WHERE id = ? AND tenant_id = ?
                    """, result -> result.next() ? new ResourceState(result.getString("status"),
                    result.getLong("current_version")) : null, task.resourceId(), task.tenantId());
            status = state == null ? null : state.status();
            version = state == null ? null : state.version();
        }
        if (version == null || version != task.resourceVersion()
                || task.taskType() != PlatformTaskType.APPEAL_REVIEW && !"PENDING_REVIEW".equals(status)) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_VERSION_OR_STATE_STALE",
                    PlatformError.VERSION_CONFLICT);
        }
    }

    /**
     * 拒绝申请创建人、组织成员、申报冲突者及独立复核的原处理参与人。
     * @param task 待检查任务
     * @param reasonCode 本次审计使用的稳定原因码
     * @throws BusinessException 已知或申报利益冲突
     */
    private void ensureNoConflict(TaskRow task, String reasonCode) {
        long actorId = ExecutionContext.require().actorId();
        boolean declared = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM platform_task_conflict
                    WHERE task_id = ? AND subject_account_id = ?)
                """, Boolean.class, task.taskId(), actorId));
        boolean partyRelation = knownPartyRelation(task, actorId);
        boolean originalParticipation = task.taskType() == PlatformTaskType.APPEAL_REVIEW
                && participatedInOriginalReview(task, actorId);
        if (declared || partyRelation || originalParticipation) {
            rejectTaskAction(task, "platform:task:claim:conflict",
                    declared ? "DECLARED_CONFLICT" : partyRelation ? "KNOWN_PARTY_RELATION" : "ORIGINAL_PARTICIPANT",
                    PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
    }

    /**
     * 检查可由现有业务数据识别的直接关系，不推断或承诺发现所有利益关系。
     * @param task 涉及的审核或复核任务
     * @param actorId 当前平台工作人员账号
     * @return 已知申请人、自建人或事项组织成员关系存在时为 true
     */
    private boolean knownPartyRelation(TaskRow task, long actorId) {
        if ("ORGANIZATION".equals(task.resourceType())) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM business_organization o
                        WHERE o.id = ? AND o.tenant_id = ?
                          AND (o.created_by = ? OR EXISTS (
                              SELECT 1 FROM business_membership m
                              WHERE m.organization_id = o.id AND m.tenant_id = o.tenant_id
                                AND m.actor_id = ? AND m.status = 'ACTIVE'
                          ))
                    ) OR EXISTS (
                        SELECT 1 FROM business_organization_revision r
                        WHERE r.organization_id = ? AND r.tenant_id = ? AND r.created_by = ?
                    )
                    """, Boolean.class, task.resourceId(), task.tenantId(), actorId, actorId,
                    task.resourceId(), task.tenantId(), actorId));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM business_demand d
                    WHERE d.id = ? AND d.tenant_id = ?
                      AND (d.created_by = ? OR d.submitted_by = ? OR EXISTS (
                          SELECT 1 FROM business_membership m
                          WHERE m.organization_id = d.organization_id AND m.tenant_id = d.tenant_id
                            AND m.actor_id = ? AND m.status = 'ACTIVE'
                      ))
                ) OR EXISTS (
                    SELECT 1 FROM business_demand_revision r
                    WHERE r.demand_id = ? AND r.tenant_id = ? AND r.created_by = ?
                )
                """, Boolean.class, task.resourceId(), task.tenantId(), actorId, actorId, actorId,
                task.resourceId(), task.tenantId(), actorId));
    }

    /**
     * 对申诉原决定及其任何历史认领记录执行参与人排除。
     * @param task 独立复核任务
     * @param actorId 当前复核员账号
     * @return 当前账号已处理原决定或曾认领原审核任务时为 true
     */
    private boolean participatedInOriginalReview(TaskRow task, long actorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_appeal_case a
                    JOIN business_review_decision d ON d.id = a.original_decision_id
                    WHERE a.id = ? AND (d.reviewer_id = ? OR EXISTS (
                        SELECT 1 FROM platform_task_claim c
                        JOIN platform_review_task t ON t.id = c.task_id
                        WHERE t.review_case_id = a.review_case_id
                          AND t.task_type IN ('ORGANIZATION_REVIEW', 'DEMAND_REVIEW')
                          AND c.actor_id = ?
                    ))
                )
                """, Boolean.class, task.appealCaseId(), actorId, actorId));
    }

    /**
     * 确认当前账号属于待复核事项的组织申请方；平台上下文不能冒充申请组织身份。
     * @param task 申诉任务
     * @throws BusinessException 当前人不是该审核事项的一方
     */
    private void requireApplicantParty(TaskRow task) {
        DecisionRow decision = new DecisionRow(0, task.reviewCaseId(), task.tenantId(), task.resourceType(),
                task.resourceId(), task.resourceVersion(), null, null);
        requireApplicantParty(decision);
    }

    /**
     * 确认账号是原审核事项申请方或当前组织有效成员。
     * @param decision 由服务端读取的原决定与事项映射
     * @throws BusinessException 平台工作人员或无关账号尝试替申请方创建复核
     */
    private void requireApplicantParty(DecisionRow decision) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        boolean allowedContext = !"PLATFORM".equals(identity.contextType());
        boolean party = allowedContext && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_review_case c
                    WHERE c.id = ? AND c.tenant_id = ? AND (
                        c.opened_by = ? OR EXISTS (
                            SELECT 1 FROM business_membership m
                            WHERE m.organization_id = c.tenant_id AND m.tenant_id = c.tenant_id
                              AND m.actor_id = ? AND m.status = 'ACTIVE'
                        ) OR (c.resource_type = 'ORGANIZATION' AND EXISTS (
                            SELECT 1 FROM business_organization o
                            WHERE o.id = c.resource_id AND o.tenant_id = c.tenant_id AND o.created_by = ?
                        ))
                    )
                )
                """, Boolean.class, decision.reviewCaseId(), decision.tenantId(), identity.actorId(),
                identity.actorId(), identity.actorId()));
        if (!party) {
            audit.recordSecurityEvent("platform:appeal:create:denied", "review_case",
                    Long.toString(decision.reviewCaseId()), "DENIED", "APPLICANT_PARTY_REQUIRED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 校验申诉目标仍是事项当前业务版本，且版本绑定原领域决定。
     * @param decision 原审核决定与事项编号
     * @throws BusinessException 决定版本不再是当前版本
     */
    private void rejectAppeal(DecisionRow decision, String reasonCode, PlatformError error) {
        audit.record("platform:appeal:create:denied", "business_review_decision", Long.toString(decision.id()),
                "DENIED", details(decision.reviewCaseId(), null, null, decision.resourceVersion(), reasonCode));
        throw new BusinessException(error);
    }

    /**
     * 读取被复核的原审核决定并通过事项映射确认资源及组织归属。
     * @param decisionId 数据库审核决定编号
     * @return 匹配的原决定；不存在时为空
     */
    private DecisionRow loadDecision(long decisionId) {
        return jdbc.query("""
                SELECT d.id, c.id AS review_case_id, d.tenant_id, d.resource_type, d.resource_id,
                       d.resource_version, d.decision, d.reviewer_id
                FROM business_review_decision d
                JOIN platform_review_case c ON c.tenant_id = d.tenant_id
                    AND c.resource_type = d.resource_type AND c.resource_id = d.resource_id
                WHERE d.id = ?
                """, result -> result.next() ? new DecisionRow(result.getLong("id"),
                result.getLong("review_case_id"), result.getLong("tenant_id"), result.getString("resource_type"),
                result.getLong("resource_id"), result.getLong("resource_version"), result.getString("decision"),
                result.getLong("reviewer_id")) : null, decisionId);
    }

    /**
     * 读取组织申请或需求当前版本，拒绝对历史版本创建新的审核或复核工作。
     * @param resourceType 组织或需求类型
     * @param resourceId 业务对象编号
     * @param tenantId 业务资源组织范围
     * @return 当前版本
     * @throws BusinessException 业务资源不存在
     */
    private long currentVersion(String resourceType, long resourceId, long tenantId) {
        String column = "ORGANIZATION".equals(resourceType) ? "application_version" : "current_revision";
        String table = "ORGANIZATION".equals(resourceType) ? "business_organization" : "business_demand";
        Long version = jdbc.queryForObject("SELECT " + column + " FROM " + table
                + " WHERE id = ? AND tenant_id = ?", Long.class, resourceId, tenantId);
        if (version == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return version;
    }

    /**
     * 校验请求人对任务所属组织或需求主体有业务关系，用于回避请求和复核申请。
     * @param task 目标任务
     * @return 当前账号是创建人或有效组织成员
     */
    private boolean applicantParty(TaskRow task) {
        ExecutionContext.Identity identity = ExecutionContext.require();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_review_case c
                    WHERE c.id = ? AND c.tenant_id = ? AND (
                        c.opened_by = ? OR EXISTS (
                            SELECT 1 FROM business_membership m
                            WHERE m.organization_id = c.tenant_id AND m.tenant_id = c.tenant_id
                              AND m.actor_id = ? AND m.status = 'ACTIVE'
                        ) OR (c.resource_type = 'ORGANIZATION' AND EXISTS (
                            SELECT 1 FROM business_organization o
                            WHERE o.id = c.resource_id AND o.tenant_id = c.tenant_id AND o.created_by = ?
                        ))
                    )
                )
                """, Boolean.class, task.reviewCaseId(), task.tenantId(), identity.actorId(),
                identity.actorId(), identity.actorId()));
    }

    /**
     * 要求任务记录仍开放，防止在已完成或新版本取代后继续申报回避。
     * @param task 目标任务
     * @throws BusinessException 任务已完成或被版本替代
     */
    private void ensureOpenTask(TaskRow task) {
        if (!List.of("PENDING", "CLAIMED").contains(task.status())) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_NOT_OPEN", PlatformError.DATA_CONFLICT);
        }
    }

    /**
     * 要求本人当前持有任务的有效认领记录。
     * @param task 任务记录
     * @return 有效认领
     * @throws BusinessException 任务不由当前账号认领
     */
    private ClaimRequest requireOwnedClaim(TaskRow task) {
        ClaimRequest claim = activeClaim(task.taskId(), true);
        if (claim == null || claim.actorId() != ExecutionContext.require().actorId()
                || !"CLAIMED".equals(task.status())) {
            rejectTaskAction(task, "platform:task:claim:conflict", "OWNED_ACTIVE_CLAIM_REQUIRED",
                    PlatformError.ACCESS_DENIED);
        }
        return claim;
    }

    /**
     * 解析任务对应的平台审核或复核权限，并实时验证账号和岗位授权。
     * @param task 待操作任务
     */
    private void requireTaskPermission(TaskRow task) {
        authorization.requirePermission(task.taskType().permission());
    }

    /**
     * 按任务编号读取稳定任务字段，必要时串行化认领及状态转换。
     * @param taskId 任务编号
     * @param lock 是否在当前事务锁定任务行
     * @return 任务记录
     * @throws BusinessException 任务不存在
     */
    private TaskRow requireTask(long taskId, boolean lock) {
        String query = """
                SELECT id, tenant_id, review_case_id, appeal_case_id, task_type, resource_type,
                       resource_id, resource_version, status, created_at
                FROM platform_review_task WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        TaskRow task = jdbc.query(query, result -> result.next() ? mapTask(result) : null, taskId);
        if (task == null) {
            audit.recordSecurityEvent("platform:task:access:denied", "platform_review_task",
                    Long.toString(taskId), "DENIED", "TASK_NOT_FOUND");
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return task;
    }

    /**
     * 锁定并读取任务当前唯一有效认领；不存在时返回空值。
     * @param taskId 任务编号
     * @param lock 是否锁定认领行
     * @return 当前认领人及认领号，任务未认领时为空
     */
    private ClaimRequest activeClaim(long taskId, boolean lock) {
        String query = "SELECT id, actor_id, status FROM platform_task_claim"
                + " WHERE task_id = ? AND status = 'ACTIVE'" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(query, result -> result.next()
                ? new ClaimRequest(result.getLong("id"), result.getLong("actor_id"), result.getString("status"))
                : null, taskId);
    }

    /**
     * 追加幂等键到账户、任务及原认领结果的映射。
     * @param taskId 任务编号
     * @param actorId 可信当前账号
     * @param key 请求幂等键
     * @param claimId 已建立或复用的认领编号
     */
    private void insertClaimRequest(long taskId, long actorId, String key, long claimId) {
        jdbc.update("""
                INSERT INTO platform_task_claim_request (id, task_id, actor_id, idempotency_key, claim_id)
                VALUES (?, ?, ?, ?, ?)
                """, ids.nextId(), taskId, actorId, key, claimId);
    }

    /**
     * 记录认领幂等重试，不重复创建占用或修改任务状态。
     * @param task 原任务
     * @param claimId 原认领编号
     */
    private void recordClaimRetry(TaskRow task, long claimId) {
        audit.record("platform:task:claim:retry", "platform_task_claim", Long.toString(claimId), "SUCCESS",
                details(task.reviewCaseId(), task.appealCaseId(), task.taskId(), task.resourceVersion(),
                        "IDEMPOTENT_RETRY"));
    }

    /**
     * 按认领行读取的状态组装不泄漏申请材料的响应。
     * @param taskId 任务编号
     * @param claimId 认领编号
     * @param status 认领状态
     * @return 幂等认领结果
     */
    private ClaimResult claimResult(long taskId, long claimId, String status) {
        return new ClaimResult(Long.toString(taskId), Long.toString(claimId), status, "ACTIVE".equals(status));
    }

    /**
     * 结束一次认领并保存原因；终态认领由数据库触发器保护不再被改写。
     * @param task 任务
     * @param claimId 有效认领编号
     * @param status 释放、恢复、回避、完成或版本替代状态
     * @param reason 本次状态变化理由
     */
    private void closeClaim(TaskRow task, long claimId, String status, String reason) {
        int updated = jdbc.update("""
                UPDATE platform_task_claim
                SET status = ?, closed_at = clock_timestamp(), close_reason = ?
                WHERE id = ? AND task_id = ? AND status = 'ACTIVE'
                """, status, reason, claimId, task.taskId());
        if (updated != 1) {
            rejectTaskAction(task, "platform:task:claim:conflict", "ACTIVE_CLAIM_CHANGED",
                    PlatformError.DATA_CONFLICT);
        }
    }

    /**
     * 变更任务状态，数据库触发器限制其只能按批准的单向状态图流转。
     * @param task 当前任务状态
     * @param status 目标状态
     */
    private void updateTaskStatus(TaskRow task, String status) {
        int updated = jdbc.update("""
                UPDATE platform_review_task SET status = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = ?
                """, status, task.taskId(), task.status());
        if (updated != 1) {
            rejectTaskAction(task, "platform:task:claim:conflict", "TASK_STATE_CHANGED",
                    PlatformError.DATA_CONFLICT);
        }
    }

    /**
     * 使独立复核事项状态与当前任务占用状态保持一致。
     * @param task 相关任务
     * @param status 待复核或处理中
     */
    private void updateAppealStatus(TaskRow task, String status) {
        if (task.appealCaseId() != null) {
            jdbc.update("""
                    UPDATE platform_appeal_case SET status = ?, updated_at = clock_timestamp()
                    WHERE id = ? AND status <> 'REVIEWED' AND status <> 'SUPERSEDED'
                    """, status, task.appealCaseId());
        }
    }

    /**
     * 在新版本提交时关闭旧版本待办、认领及仍未处理的旧版本复核请求。
     * @param reviewCaseId 稳定事项编号
     * @param currentVersion 新提交的版本
     */
    private void supersedeOlderTasks(long reviewCaseId, long currentVersion) {
        List<Long> taskIds = jdbc.queryForList("""
                SELECT id FROM platform_review_task
                WHERE review_case_id = ? AND resource_version < ? AND status IN ('PENDING', 'CLAIMED')
                ORDER BY id FOR UPDATE
                """, Long.class, reviewCaseId, currentVersion);
        for (Long taskId : taskIds) {
            TaskRow staleTask = requireTask(taskId, true);
            ClaimRequest claim = activeClaim(taskId, true);
            if (claim != null) {
                closeClaim(staleTask, claim.claimId(), "SUPERSEDED", "RESOURCE_VERSION_SUPERSEDED");
            }
            if (staleTask.appealCaseId() != null) {
                jdbc.update("""
                        UPDATE platform_appeal_case SET status = 'SUPERSEDED', updated_at = clock_timestamp()
                        WHERE id = ? AND status IN ('PENDING', 'IN_REVIEW')
                        """, staleTask.appealCaseId());
            }
            updateTaskStatus(staleTask, "SUPERSEDED");
            audit.record("platform:task:superseded", "platform_review_task", Long.toString(taskId), "SUCCESS",
                    details(staleTask.reviewCaseId(), staleTask.appealCaseId(), taskId,
                            staleTask.resourceVersion(), "RESOURCE_VERSION_SUPERSEDED"));
        }
    }

    /**
     * 校验提交事务中的组织或需求状态仍为该版本待审核。
     * @param type 组织或需求类型
     * @param resourceId 业务对象编号
     * @param tenantId 归属组织
     * @param version 当前提交版本
     * @return 当前对象状态及版本匹配
     */
    private boolean isCurrentSubmission(ReviewCaseType type, long resourceId, long tenantId, long version) {
        if (type == ReviewCaseType.ORGANIZATION) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM business_organization
                        WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW' AND application_version = ?)
                    """, Boolean.class, resourceId, tenantId, version));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM business_demand
                    WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW' AND current_revision = ?)
                """, Boolean.class, resourceId, tenantId, version));
    }

    /**
     * 生成可用于任务生命周期和创建事件的受控审计详情。
     * @param reviewCaseId 审核事项编号
     * @param appealCaseId 独立复核编号
     * @param taskId 任务编号，可为空
     * @param version 业务版本
     * @param reasonCode 稳定原因码
     * @return 不包含自由文本正文的审计详情
     */
    private AuditRecorder.AuditDetails details(long reviewCaseId, Long appealCaseId, Long taskId,
                                               long version, String reasonCode) {
        return new AuditRecorder.AuditDetails(Long.toString(reviewCaseId),
                appealCaseId == null ? null : Long.toString(appealCaseId), null,
                taskId == null ? null : Long.toString(taskId), version, null, reasonCode, null);
    }

    /**
     * 对失败的对象级任务操作先持久化拒绝事件，再返回统一业务错误。
     * @param task 已定位的任务
     * @param action 固定审计动作
     * @param reasonCode 稳定拒绝原因
     * @param error 向调用方返回的业务错误
     * @throws BusinessException 始终抛出传入的错误
     */
    private void rejectTaskAction(TaskRow task, String action, String reasonCode, PlatformError error) {
        audit.record(action, "platform_review_task", Long.toString(task.taskId()),
                error == PlatformError.DATA_CONFLICT || error == PlatformError.VERSION_CONFLICT ? "CONFLICT" : "DENIED",
                details(task.reviewCaseId(), task.appealCaseId(), task.taskId(), task.resourceVersion(), reasonCode));
        throw new BusinessException(error);
    }

    /**
     * 对复核申请冲突记录独立拒绝事件并终止本次请求。
     * @param decision 目标原决定
     * @param reasonCode 稳定拒绝原因
     * @param error 返回给调用方的业务错误
     * @throws BusinessException 始终抛出传入错误
     */
    private void rejectAppeal(DecisionRow decision, String reasonCode, RuntimeException error) {
        audit.record("platform:appeal:create:denied", "business_review_decision", Long.toString(decision.id()),
                "CONFLICT", details(decision.reviewCaseId(), null, null, decision.resourceVersion(), reasonCode));
        throw error;
    }

    /**
     * 将审核事项当前资源和版本转换为稳定审核任务行。
     * @param result SQL 当前行
     * @return 不包含申请正文的任务记录
     * @throws SQLException 数据库列读取失败
     */
    private TaskRow mapTask(ResultSet result) throws SQLException {
        return new TaskRow(result.getLong("id"), result.getLong("tenant_id"), result.getLong("review_case_id"),
                nullableLong(result, "appeal_case_id"), PlatformTaskType.fromCode(result.getString("task_type")),
                result.getString("resource_type"), result.getLong("resource_id"),
                result.getLong("resource_version"), result.getString("status"),
                result.getTimestamp("created_at").toInstant());
    }

    /**
     * 安全读取可空 bigint，不把 SQL NULL 转换成零号身份或关联。
     * @param result SQL 当前行
     * @param column 列名
     * @return bigint 值或 null
     * @throws SQLException 数据库列读取失败
     */
    private Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * 为队列摘要读取可空编号。
     * @param result SQL 当前行
     * @param column 列名
     * @return 十进制编号或 null
     * @throws SQLException 数据库列读取失败
     */
    private String stringValue(ResultSet result, String column) throws SQLException {
        Long value = nullableLong(result, column);
        return value == null ? null : Long.toString(value);
    }

    /**
     * 校验申请或释放原因非空且长度受限，不将自由文本送入标识型审计字段。
     * @param reason 用户输入的原因
     * @return 去除首尾空格后的原因
     * @throws BusinessException 原因为空或超过数据库上限
     */
    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 2000) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return reason.trim();
    }

    /**
     * 校验所有业务标识均为正数。
     * @param values 一组编号或版本
     * @throws IllegalArgumentException 任一编号不为正数
     */
    private void validatePositive(long... values) {
        for (long value : values) {
            requirePositive(value);
        }
    }

    /**
     * 校验单个编号为正数。
     * @param value 待检查编号
     * @throws IllegalArgumentException 编号不为正数
     */
    private void requirePositive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Task and decision identifiers must be positive");
        }
    }

    /**
     * 创建任务队列摘要；未认领任务不返回持有人或详情字段。
     * @param taskId 任务编号
     * @param taskType 岗位任务类型
     * @param reviewCaseId 审核事项编号
     * @param appealCaseId 独立复核编号
     * @param resourceType 资源种类
     * @param resourceVersion 绑定版本
     * @param status 当前状态
     * @param claimedByMe 当前请求人是否为认领人
     * @param createdAt 创建时间
     */
    public record QueueItem(String taskId, String taskType, String reviewCaseId, String appealCaseId,
                            String resourceType, String resourceVersion, String status, boolean claimedByMe,
                            Instant createdAt) {
    }

    /**
     * 一次认领的结果；已释放的相同幂等请求返回原 claimId 且 active 为 false。
     * @param taskId 任务编号
     * @param claimId 稳定认领编号
     * @param status 原认领当前状态
     * @param active 当前是否仍持有占用
     */
    public record ClaimResult(String taskId, String claimId, String status, boolean active) {
    }

    /**
     * 认领后可读的最小任务详情及同事项内部处理历史。
     * @param task 任务标识、资源版本及当前状态
     * @param history 仅限该已认领事项的内部版本和审计历史
     * @param appeal 申诉任务所需的原决定和申请理由；普通审核任务为空
     */
    public record TaskDetails(TaskView task, View history, AppealSummary appeal) {
    }

    /**
     * 认领人员在任务范围内可见的稳定任务信息。
     * @param taskId 任务编号
     * @param taskType 任务类型
     * @param reviewCaseId 原审核事项
     * @param appealCaseId 独立复核事项，可为空
     * @param resourceType 原业务对象类型
     * @param resourceId 原业务对象编号
     * @param resourceVersion 被处理业务版本
     * @param status 当前任务状态
     * @param createdAt 任务首次创建时间
     */
    public record TaskView(String taskId, String taskType, String reviewCaseId, String appealCaseId,
                           String resourceType, String resourceId, long resourceVersion,
                           String status, Instant createdAt) {
    }

    /**
     * 独立复核人获授权后查看的复核申请和原决定字段白名单。
     * @param appealCaseId 复核事项编号
     * @param originalDecisionId 被复核决定编号
     * @param requestedBy 申请方账号编号
     * @param requestReason 申请方提出的复核理由
     * @param originalDecision 原决定代码
     * @param originalStatus 原决定目标状态
     * @param originalReviewerId 原审核人账号编号
     * @param originalDecisionAt 原决定时间
     */
    public record AppealSummary(String appealCaseId, String originalDecisionId, String requestedBy,
                                String requestReason, String originalDecision, String originalStatus,
                                String originalReviewerId, Instant originalDecisionAt) {
    }

    /**
     * 申请方创建独立复核事项后的编号和待复核状态。
     * @param appealCaseId 复核事项编号
     * @param taskId 独立复核岗位任务编号
     * @param resourceVersion 被申请复核的原决定版本
     * @param status 当前复核状态
     */
    public record AppealCreated(String appealCaseId, String taskId, long resourceVersion, String status) {
    }

    /**
     * 独立复核员形成的意见结果；不直接写入组织或需求业务状态。
     * @param resultId 复核意见编号
     * @param appealCaseId 复核事项编号
     * @param outcome 意见类型
     * @param reviewerId 实际复核人员
     * @param reason 复核理由
     * @param reviewedAt 服务端记录的完成时间
     */
    public record AppealReviewResult(String resultId, String appealCaseId, String outcome,
                                     long reviewerId, String reason, Instant reviewedAt) {
    }

    /**
     * 无材料内容的异常任务恢复队列行。
     * @param taskId 当前占用任务编号
     * @param taskType 原岗位任务种类
     * @param status 任务占用状态
     * @param claimedAt 当前认领开始时间
     */
    public record RecoveryItem(String taskId, String taskType, String status, Instant claimedAt) {
    }

    /**
     * 原认领人可见的异常恢复通知，不包含其他人员、申请材料或内部恢复理由。
     * @param noticeId 唯一通知编号
     * @param taskId 原审核任务编号
     * @param taskType 原岗位任务种类
     * @param claimedAt 原认领开始时间
     * @param recoveredAt 服务端通知创建时间
     */
    public record RecoveryNotice(String noticeId, String taskId, String taskType,
                                 Instant claimedAt, Instant recoveredAt) {
    }

    /**
     * 不可变任务生命周期引用，供领域审核状态服务在同一事务中完成任务。
     * @param taskId 任务编号
     * @param reviewCaseId 审核事项编号
     * @param resourceVersion 审核版本
     * @param taskType 当前审核任务类型
     */
    public record TaskRef(long taskId, long reviewCaseId, long resourceVersion, PlatformTaskType taskType) {
    }

    /** 已通过当前岗位及认领检查的材料访问任务快照。 */
    public record EvidenceTaskScope(long taskId, long tenantId, long reviewCaseId, String resourceType,
                                    long resourceId, long resourceVersion, String taskType) {
        /**
         * 校验任务范围中的持久化标识。
         * @param taskId 有效任务编号
         * @param tenantId 资源所属组织编号
         * @param reviewCaseId 稳定事项编号
         * @param resourceType 组织或需求资源类型
         * @param resourceId 业务资源编号
         * @param resourceVersion 当前审核版本
         * @param taskType 普通审核或独立复核任务类型
         */
        public EvidenceTaskScope {
            if (taskId <= 0 || tenantId <= 0 || reviewCaseId <= 0 || resourceId <= 0 || resourceVersion <= 0
                    || !List.of("ORGANIZATION", "DEMAND").contains(resourceType)
                    || !List.of("ORGANIZATION_REVIEW", "DEMAND_REVIEW", "APPEAL_REVIEW").contains(taskType)) {
                throw new IllegalArgumentException("Invalid evidence task scope");
            }
        }
    }

    /** 独立复核意见代码；“请求重审”只是流程意见，不自动改变原业务状态。 */
    public enum AppealOutcome {
        /** 独立复核意见为维持原审核结论。 */
        UPHOLD_ORIGINAL,
        /** 独立复核意见为退回对应业务岗位重新处理。 */
        REQUEST_RECONSIDERATION
    }

    /**
     * 稳定任务数据库行，仅服务端事务内部使用。
     * @param taskId 任务编号
     * @param tenantId 资源所属组织
     * @param reviewCaseId 稳定审核事项编号
     * @param appealCaseId 独立复核事项编号，可为空
     * @param taskType 任务岗位类型
     * @param resourceType 原始业务对象类型
     * @param resourceId 原始业务对象编号
     * @param resourceVersion 任务绑定版本
     * @param status 任务生命周期状态
     * @param createdAt 首次创建时间
     */
    private record TaskRow(long taskId, long tenantId, long reviewCaseId, Long appealCaseId,
                           PlatformTaskType taskType, String resourceType, long resourceId,
                           long resourceVersion, String status, Instant createdAt) {
        /**
         * 映射对当前授权处理人可见的基础任务字段。
         * @return 不包含申请材料的任务视图
         */
        private TaskView publicView() {
            return new TaskView(Long.toString(taskId), taskType.name(), Long.toString(reviewCaseId),
                    appealCaseId == null ? null : Long.toString(appealCaseId), resourceType,
                    Long.toString(resourceId), resourceVersion, status, createdAt);
        }

        /**
         * 生成领域审核服务使用的不可变任务引用。
         * @return 审核事务中的任务引用
         */
        private TaskRef reference() {
            return new TaskRef(taskId, reviewCaseId, resourceVersion, taskType);
        }
    }

    /**
     * 当前认领、认领幂等映射或组织一方关系查询结果。
     * @param claimId 认领编号
     * @param actorId 当前认领账号
     * @param status 认领状态
     */
    private record ClaimRequest(long claimId, long actorId, String status) {
        /**
         * 构造无账号字段的幂等响应行。
         * @param claimId 原认领编号
         * @param status 原认领状态
         */
        private ClaimRequest(long claimId, String status) {
            this(claimId, 0L, status);
        }
    }

    /**
     * 事项当前资源状态与版本。
     * @param status 领域状态
     * @param version 当前业务版本
     */
    private record ResourceState(String status, long version) {
    }

    /**
     * 由服务端关联审核事项后的原决定快照。
     * @param id 原审核决定编号
     * @param reviewCaseId 稳定审核事项编号
     * @param tenantId 资源所属组织
     * @param resourceType 组织或需求类型
     * @param resourceId 原始业务对象编号
     * @param resourceVersion 原决定绑定版本
     * @param decision 原审核结论
     * @param reviewerId 原审核人员
     */
    private record DecisionRow(long id, long reviewCaseId, long tenantId, String resourceType,
                               long resourceId, long resourceVersion, String decision, Long reviewerId) {
    }
}
