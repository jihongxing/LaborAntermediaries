package com.company.labor.foundation.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 按显式启动配置在数据库恢复后执行本地审计故障对账。 */
@Component
@ConditionalOnProperty(prefix = "audit.failure-recovery", name = "enabled", havingValue = "true")
public class AuditFailureRecoveryRunner implements ApplicationRunner {
    /** 恢复流程的无敏感摘要日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AuditFailureRecoveryRunner.class);
    /** 执行幂等对账和补记。 */
    private final AuditFailureRecoveryService recoveryService;

    /**
     * 创建启动期恢复任务。
     * @param recoveryService 对账与补记服务
     */
    public AuditFailureRecoveryRunner(AuditFailureRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    /**
     * 启动时完成故障日志恢复；任何坏行或数据库错误都会使本次启动失败并保留原文件。
     * @param arguments Spring Boot 启动参数
     */
    @Override
    public void run(ApplicationArguments arguments) {
        AuditFailureRecoveryService.RecoveryReport report = recoveryService.reconcile();
        LOG.warn("AUDIT_FAILURE_RECOVERY inspected={} backfilled={} alreadyPresent={} previouslyReconciled={}",
                report.inspected(), report.backfilled(), report.alreadyPresent(), report.previouslyReconciled());
    }
}
