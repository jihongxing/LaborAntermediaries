package com.company.labor.foundation.audit;

import com.company.labor.foundation.context.ExecutionContext;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

/** 将标识化审计事件写入数据库，使用调用线程的身份和请求标识，不接受任意敏感正文。 */
@Service
public class AuditRecorder {
    /** 独立数据库审计失败时写入本地持久化错误日志，供故障窗口核对。 */
    private static final Logger LOG = LoggerFactory.getLogger(AuditRecorder.class);
    /** 审计持久化入口，写入参与调用方当前数据库事务。 */
    private final JdbcTemplate jdbc;
    /** 独立事务管理器，用于让失败/拒绝事件不随业务事务回滚。 */
    private final PlatformTransactionManager transactionManager;
    /** 独立本地持久化通道，在数据库审计写入失败时保存可恢复记录。 */
    private final AuditFailureLog failureLog;

    /**
     * 创建审计记录器。
     * @param jdbc 数据库访问模板
     * @param transactionManager 数据库事务管理器
     * @param failureLog 数据库之外的故障日志
     */
    public AuditRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, AuditFailureLog failureLog) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.failureLog = failureLog;
    }

    /**
     * 写入当前操作者的审计事件；成功事件参与业务事务，拒绝或失败事件使用独立事务提交。
     * @param action 操作标识，最长 100 字符
     * @param resourceType 资源类型标识，最长 100 字符
     * @param resourceId 资源标识，最长 200 字符，不得携带敏感正文
     * @param outcome SUCCESS、DENIED 或 FAILED
     * @throws IllegalArgumentException 标识或结果不合法
     * @throws com.company.labor.common.exception.BusinessException 当前未认证
     */
    public void record(String action, String resourceType, String resourceId, String outcome) {
        record(action, resourceType, resourceId, outcome, AuditDetails.empty());
    }

    /**
     * 写入可关联业务事项、版本、任务和访问用途的审计事件。
     * @param action 操作标识
     * @param resourceType 资源类型
     * @param resourceId 资源标识
     * @param outcome SUCCESS、DENIED、FAILED 或 CONFLICT
     * @param details 可选事项和访问上下文
     */
    public void record(String action, String resourceType, String resourceId, String outcome, AuditDetails details) {
        requireIdentifier(action, 100);
        requireIdentifier(resourceType, 100);
        requireIdentifier(resourceId, 200);
        if (!Set.of("SUCCESS", "DENIED", "FAILED", "CONFLICT").contains(outcome)) {
            throw new IllegalArgumentException("Invalid audit outcome");
        }
        validateDetails(details);
        ExecutionContext.Identity identity = ExecutionContext.require();
        AuditDetails attributedDetails = details.actorRole() == null && identity.actorRole() != null
                ? new AuditDetails(details.reviewCaseId(), details.appealCaseId(), details.findingId(), details.taskId(),
                details.resourceVersion(), identity.actorRole(), details.reasonCode(), details.purposeCode()) : details;
        String actorType = "PLATFORM".equals(identity.contextType()) ? "PLATFORM" : "PERSON";
        if (!"SUCCESS".equals(outcome)) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> insert(identity.actorId(), identity.tenantId(), actorType,
                    action, resourceType, resourceId, outcome, attributedDetails));
            return;
        }
        insert(identity.actorId(), identity.tenantId(), actorType, action, resourceType, resourceId,
                outcome, attributedDetails);
    }

    /**
     * 记录由受控部署命令执行的授权操作；成功事件加入业务事务，失败事件独立提交。
     * @param operatorId 由部署流程提供的操作者编号
     * @param action 固定审计动作
     * @param resourceType 资源类型
     * @param resourceId 非敏感资源标识
     * @param outcome SUCCESS、DENIED 或 FAILED
     * @param reasonCode 稳定原因码，不包含自由文本
     */
    public void recordServiceEvent(long operatorId, String action, String resourceType, String resourceId,
                                   String outcome, String reasonCode) {
        if (operatorId <= 0 || !Set.of("SUCCESS", "DENIED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("A positive operator and valid audit outcome are required");
        }
        requireIdentifier(action, 100);
        requireIdentifier(resourceType, 100);
        requireIdentifier(resourceId, 200);
        requireIdentifier(reasonCode, 100);
        AuditDetails details = new AuditDetails(null, null, null, null, null, null, reasonCode, null);
        if (!"SUCCESS".equals(outcome)) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> insert(operatorId, null, "SERVICE", action,
                    resourceType, resourceId, outcome, details));
            return;
        }
        insert(operatorId, null, "SERVICE", action, resourceType, resourceId, outcome, details);
    }

    /**
     * 记录认证前无法关联账号的安全拒绝；不使用虚构的系统账号编号。
     * @param action 操作标识
     * @param resourceType 资源类型
     * @param resourceId 不包含路径参数的稳定资源标识
     * @param outcome DENIED 或 FAILED
     * @param reasonCode 稳定拒绝原因码
     */
    public void recordAnonymous(String action, String resourceType, String resourceId, String outcome, String reasonCode) {
        requireIdentifier(action, 100);
        requireIdentifier(resourceType, 100);
        requireIdentifier(resourceId, 200);
        requireIdentifier(reasonCode, 100);
        if (!Set.of("DENIED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("Anonymous audit outcome must be DENIED or FAILED");
        }
        AuditDetails details = new AuditDetails(null, null, null, null, null, null, reasonCode, null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> insert(null, null, "ANONYMOUS", action, resourceType,
                resourceId, outcome, details));
    }

    /**
     * 按当前身份或匿名状态记录安全拒绝，供 MVC 与安全过滤链共用。
     * @param action 操作标识
     * @param resourceType 资源类型
     * @param resourceId 不包含路径参数的稳定资源标识
     * @param outcome DENIED 或 FAILED
     * @param reasonCode 稳定拒绝原因码
     */
    public void recordSecurityEvent(String action, String resourceType, String resourceId,
                                    String outcome, String reasonCode) {
        AuditDetails details = new AuditDetails(null, null, null, null, null, null, reasonCode, null);
        if (ExecutionContext.isPresent()) {
            record(action, resourceType, resourceId, outcome, details);
        } else {
            recordAnonymous(action, resourceType, resourceId, outcome, reasonCode);
        }
    }

    /**
     * 写入一条事件；调用方决定是否处于当前事务或独立事务。
     * @param actorId 账号标识，匿名事件为空
     * @param tenantId 组织标识，跨组织/匿名事件为空
     * @param actorType PERSON、PLATFORM、SERVICE 或 ANONYMOUS
     * @param action 操作标识
     * @param resourceType 资源类型
     * @param resourceId 资源标识
     * @param outcome 操作结果
     * @param details 可选事项和访问上下文
     */
    private void insert(Long actorId, Long tenantId, String actorType, String action, String resourceType,
                        String resourceId, String outcome, AuditDetails details) {
        String requestId = MDC.get("requestId");
        String correlationId = correlationId();
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> append(actorId, tenantId, actorType, action, resourceType,
                    resourceId, outcome, details, requestId, correlationId));
        } catch (RuntimeException exception) {
            AuditFailureRecord failure = AuditFailureRecord.create(actorId, actorType, tenantId, action,
                    resourceType, resourceId, outcome, requestId, correlationId, details, exception);
            try {
                failureLog.append(failure);
            } catch (RuntimeException persistenceException) {
                LOG.error("AUDIT_FAILURE_CHANNEL_FAILURE failureId={} requestId={} correlationId={} exceptionType={}",
                        failure.failureId(), requestId, correlationId, persistenceException.getClass().getSimpleName());
            }
            LOG.error("AUDIT_APPEND_FAILURE requestId={} correlationId={} actorType={} actorId={} tenantId={} "
                            + "action={} resourceType={} outcome={} failureId={} exceptionType={}", requestId,
                    correlationId, actorType, actorId, tenantId, action, resourceType, outcome,
                    failure.failureId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /**
     * 数据库恢复后核对一条故障记录，并幂等补记未持久化的尝试结果。
     * @param failure 独立故障文件中的记录
     * @return 本次恢复结果
     * @throws IllegalArgumentException 故障记录字段不符合审计标识约束
     */
    public RecoveryResult reconcileFailure(AuditFailureRecord failure) {
        validateFailure(failure);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            // 先锁链头再检查去重，多个恢复实例也不会重复补记同一条失败记录。
            jdbc.queryForObject("SELECT last_hash FROM foundation_audit_chain WHERE chain_id = 1 FOR UPDATE",
                    (result, rowNumber) -> result.getString("last_hash"));
            Integer reconciled = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM foundation_audit
                    WHERE action = 'foundation:audit:failure:reconciled'
                        AND resource_type = 'audit_failure' AND resource_id = ?
                    """, Integer.class, failure.failureId());
            if (reconciled != null && reconciled > 0) {
                return RecoveryResult.ALREADY_RECONCILED;
            }

            Integer originalEvents = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM foundation_audit
                    WHERE correlation_id = ? AND action = ? AND resource_type = ? AND resource_id = ?
                        AND actor_id IS NOT DISTINCT FROM ? AND tenant_id IS NOT DISTINCT FROM ?
                    """, Integer.class, failure.correlationId(), failure.action(), failure.resourceType(),
                    failure.resourceId(), failure.actorId(), failure.tenantId());
            boolean backfilled = originalEvents == null || originalEvents == 0;
            if (backfilled) {
                String recoveredOutcome = "SUCCESS".equals(failure.attemptedOutcome())
                        ? "FAILED" : failure.attemptedOutcome();
                AuditDetails details = withReasonCode(failure.details(), "AUDIT_APPEND_FAILURE");
                append(failure.actorId(), failure.tenantId(), failure.actorType(), failure.action(),
                        failure.resourceType(), failure.resourceId(), recoveredOutcome, details,
                        failure.requestId(), failure.correlationId());
            }

            AuditDetails recoveryDetails = new AuditDetails(failure.details().reviewCaseId(),
                    failure.details().appealCaseId(), failure.details().findingId(), failure.details().taskId(),
                    failure.details().resourceVersion(), failure.details().actorRole(),
                    backfilled ? "BACKFILLED" : "EVENT_ALREADY_PRESENT", failure.details().purposeCode());
            append(failure.actorId(), failure.tenantId(), failure.actorType(),
                    "foundation:audit:failure:reconciled", "audit_failure", failure.failureId(), "SUCCESS",
                    recoveryDetails, failure.requestId(), failure.correlationId());
            return backfilled ? RecoveryResult.BACKFILLED : RecoveryResult.EVENT_ALREADY_PRESENT;
        });
    }

    /** 校验独立故障文件中的所有标识，避免恢复过程将篡改正文写入审计链。 */
    private void validateFailure(AuditFailureRecord failure) {
        if (failure == null || failure.failedAt() == null || failure.actorType() == null
                || !Set.of("PERSON", "PLATFORM", "SERVICE", "ANONYMOUS").contains(failure.actorType())
                || ("ANONYMOUS".equals(failure.actorType()) != (failure.actorId() == null))
                || (failure.actorId() != null && failure.actorId() <= 0)
                || (failure.tenantId() != null && failure.tenantId() <= 0)
                || !Set.of("SUCCESS", "DENIED", "FAILED", "CONFLICT").contains(failure.attemptedOutcome())) {
            throw new IllegalArgumentException("Invalid audit failure record");
        }
        requireIdentifier(failure.failureId(), 36);
        requireIdentifier(failure.action(), 100);
        requireIdentifier(failure.resourceType(), 100);
        requireIdentifier(failure.resourceId(), 200);
        requireIdentifier(failure.correlationId(), 64);
        requireOptionalIdentifier(failure.requestId(), 64);
        requireIdentifier(failure.exceptionType(), 100);
        validateDetails(failure.details());
    }

    /** 复制审计关联并替换稳定原因码，不改写其它可追溯字段。 */
    private AuditDetails withReasonCode(AuditDetails details, String reasonCode) {
        return new AuditDetails(details.reviewCaseId(), details.appealCaseId(), details.findingId(), details.taskId(),
                details.resourceVersion(), details.actorRole(), reasonCode, details.purposeCode());
    }

    /** Serializes appends through the chain-head row and commits the event with its caller's transaction. */
    private void append(Long actorId, Long tenantId, String actorType, String action, String resourceType,
                        String resourceId, String outcome, AuditDetails details, String requestId,
                        String correlationId) {
        ChainHead head = jdbc.queryForObject("""
                SELECT last_hash FROM foundation_audit_chain WHERE chain_id = 1 FOR UPDATE
                """, (result, rowNumber) -> new ChainHead(result.getString("last_hash")));
        Long eventId = jdbc.queryForObject("SELECT nextval('foundation_id_seq')", Long.class);
        Timestamp createdAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        AuditEventHash.Event event = new AuditEventHash.Event(eventId, actorId, actorType, tenantId, action,
                resourceType, resourceId, outcome, requestId, createdAt.toInstant(), details.reviewCaseId(),
                details.appealCaseId(), details.findingId(), details.taskId(), details.resourceVersion(),
                details.actorRole(), correlationId, details.reasonCode(), details.purposeCode(), head.lastHash());
        String eventHash = AuditEventHash.calculate(event);
        jdbc.update("""
                INSERT INTO foundation_audit(actor_id, actor_type, tenant_id, action, resource_type, resource_id,
                    outcome, request_id, review_case_id, appeal_case_id, finding_id, task_id, resource_version,
                    actor_role, correlation_id, reason_code, purpose_code, id, created_at, previous_hash, event_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, actorId, actorType, tenantId, action, resourceType, resourceId, outcome, requestId,
                details.reviewCaseId(), details.appealCaseId(), details.findingId(), details.taskId(),
                details.resourceVersion(), details.actorRole(), correlationId, details.reasonCode(),
                details.purposeCode(), event.id(), createdAt, event.previousHash(), eventHash);
    }

    /**
     * 校验关联字段只包含标识信息，阻止把个人资料或任意正文写入审计索引。
     * @param details 待检查的审计关联
     * @throws IllegalArgumentException 关联字段含正文格式或版本非法
     */
    private void validateDetails(AuditDetails details) {
        if (details == null || (details.resourceVersion() != null && details.resourceVersion() <= 0)) {
            throw new IllegalArgumentException("Invalid audit details");
        }
        requireOptionalIdentifier(details.reviewCaseId(), 64);
        requireOptionalIdentifier(details.appealCaseId(), 64);
        requireOptionalIdentifier(details.findingId(), 64);
        requireOptionalIdentifier(details.taskId(), 64);
        requireOptionalIdentifier(details.actorRole(), 64);
        requireOptionalIdentifier(details.reasonCode(), 100);
        requireOptionalIdentifier(details.purposeCode(), 100);
    }

    /**
     * 对可空关联字段应用与基础标识相同的字符白名单。
     * @param value 可选标识
     * @param maximum 最大字符数
     */
    private void requireOptionalIdentifier(String value, int maximum) {
        if (value != null) {
            requireIdentifier(value, maximum);
        }
    }

    /** 优先复用单次请求服务端生成的关联号；非 HTTP 调用生成独立 UUID。 */
    private String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    /**
     * 限制审计字段的字符集和长度，减少误记正文与注入日志内容的风险。
     * @param value 待校验标识
     * @param maximum 最大字符数
     * @throws IllegalArgumentException 标识为空或格式不合法
     */
    private void requireIdentifier(String value, int maximum) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1," + maximum + "}")) {
            throw new IllegalArgumentException("Audit fields must contain identifiers, not arbitrary sensitive content");
        }
    }

    /**
     * 审计事件可选的事项、版本、岗位、原因和访问目的关联。
     * @param reviewCaseId 长周期审核事项标识
     * @param appealCaseId 独立异议复核标识
     * @param findingId 监察发现标识
     * @param taskId 岗位任务标识
     * @param resourceVersion 被访问或处理的资源版本
     * @param actorRole 操作时的平台岗位
     * @param reasonCode 稳定原因码，不承载自由文本
     * @param purposeCode 读取/展开数据的用途码
     */
    public record AuditDetails(
            String reviewCaseId,
            String appealCaseId,
            String findingId,
            String taskId,
            Long resourceVersion,
            String actorRole,
            String reasonCode,
            String purposeCode) {
        /** 创建不带业务关联字段的基础事件详情。 */
        public static AuditDetails empty() {
            return new AuditDetails(null, null, null, null, null, null, null, null);
        }
    }

    /** 故障日志恢复后的幂等处理结果。 */
    public enum RecoveryResult {
        /** 原事件缺失，本次已补记并写入恢复标记。 */
        BACKFILLED,
        /** 原事件已存在，本次写入核对结果标记。 */
        EVENT_ALREADY_PRESENT,
        /** 同一故障记录此前已经完成核对。 */
        ALREADY_RECONCILED
    }

    /** Previous sealed event read while holding the chain-head row lock. */
    private record ChainHead(String lastHash) {
    }
}
