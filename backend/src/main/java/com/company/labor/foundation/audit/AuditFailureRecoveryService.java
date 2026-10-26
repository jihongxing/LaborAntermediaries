package com.company.labor.foundation.audit;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/** 在数据库恢复后将独立故障日志逐条核对并补记到哈希链。 */
@Service
public class AuditFailureRecoveryService {
    /** 独立文件中的原始故障记录。 */
    private final AuditFailureLog failureLog;
    /** 将核对和补记结果写入数据库审计链。 */
    private final AuditRecorder auditRecorder;

    /**
     * 创建故障恢复服务。
     * @param failureLog 故障文件
     * @param auditRecorder 数据库审计记录器
     */
    public AuditFailureRecoveryService(AuditFailureLog failureLog, AuditRecorder auditRecorder) {
        this.failureLog = failureLog;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 核对故障文件并幂等补记缺失事件；坏行或数据库错误会中止恢复，不跳过失败。
     * @return 检查、补记、已存在及此前已恢复的记录数量
     */
    public RecoveryReport reconcile() {
        AtomicLong inspected = new AtomicLong();
        AtomicLong backfilled = new AtomicLong();
        AtomicLong alreadyPresent = new AtomicLong();
        AtomicLong previouslyReconciled = new AtomicLong();
        failureLog.readEach(record -> {
            inspected.incrementAndGet();
            switch (auditRecorder.reconcileFailure(record)) {
                case BACKFILLED -> backfilled.incrementAndGet();
                case EVENT_ALREADY_PRESENT -> alreadyPresent.incrementAndGet();
                case ALREADY_RECONCILED -> previouslyReconciled.incrementAndGet();
            }
        });
        return new RecoveryReport(inspected.get(), backfilled.get(), alreadyPresent.get(),
                previouslyReconciled.get());
    }

    /**
     * 对单条记录执行幂等核对，供隔离测试和受控恢复工具使用。
     * @param failure 独立文件中的审计失败记录
     * @return 本次恢复结果
     */
    public AuditRecorder.RecoveryResult reconcile(AuditFailureRecord failure) {
        return auditRecorder.reconcileFailure(failure);
    }

    /** 恢复运行的计数摘要，不包含账号、组织或资源标识。 */
    /**
     * 恢复运行的计数摘要，不包含账号、组织或资源标识。
     * @param inspected 已检查记录数
     * @param backfilled 已补记记录数
     * @param alreadyPresent 原事件已存在的记录数
     * @param previouslyReconciled 此前已对账的记录数
     */
    public record RecoveryReport(long inspected, long backfilled, long alreadyPresent, long previouslyReconciled) {
    }
}
