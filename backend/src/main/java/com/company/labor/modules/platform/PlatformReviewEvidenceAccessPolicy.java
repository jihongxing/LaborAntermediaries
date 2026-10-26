package com.company.labor.modules.platform;

import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.storage.FileAccessPolicy;
import com.company.labor.foundation.storage.PlatformFileAccessScope;
import com.company.labor.foundation.storage.PrivateFileStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 仅为当前有效认领任务放行已绑定、扫描通过且版本匹配的平台审核材料。 */
@Component
public class PlatformReviewEvidenceAccessPolicy implements FileAccessPolicy {
    /** 证据绑定、租户和任务关系的显式限域查询入口。 */
    private final JdbcTemplate jdbc;
    /** 每次文件读取前重新核验实时授权、认领、版本及回避状态。 */
    private final PlatformTaskService tasks;

    /**
     * 创建只支持平台证据访问的文件策略。
     * @param jdbc 显式限域的证据和任务查询入口
     * @param tasks 平台任务实时授权服务
     */
    public PlatformReviewEvidenceAccessPolicy(JdbcTemplate jdbc, PlatformTaskService tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    /**
     * 平台策略不扩大普通组织文件读取范围。
     * @param identity 当前身份
     * @param file 当前租户中的文件元数据
     * @return 始终拒绝由本策略放行
     */
    @Override
    public boolean mayRead(ExecutionContext.Identity identity, PrivateFileStorage.Metadata file) {
        return false;
    }

    /**
     * 再次验证文件确实属于当前任务的同租户、同对象和同版本证据记录。
     * @param identity 当前平台审核身份
     * @param file 已由私有存储按可信租户条件查询的文件
     * @param scope 服务端生成的任务访问快照
     * @return 只有任务仍由当前账号有效认领且证据绑定全部匹配时为 true
     */
    @Override
    public boolean mayReadForPlatform(ExecutionContext.Identity identity, PrivateFileStorage.Metadata file,
                                      PlatformFileAccessScope scope) {
        if (!"PLATFORM".equals(identity.contextType()) || identity.tenantId() != null
                || identity.sessionId() == null || scope.purposeCode() == null
                || !scope.purposeCode().matches("[A-Z_]{1,40}")) {
            return false;
        }
        PlatformTaskService.EvidenceTaskScope current = tasks.requireEvidenceTask(scope.taskId());
        if (current.tenantId() != scope.tenantId() || current.reviewCaseId() != scope.reviewCaseId()
                || current.resourceId() != scope.resourceId() || current.resourceVersion() != scope.resourceVersion()
                || !current.resourceType().equals(scope.resourceType()) || !current.taskType().equals(scope.taskType())) {
            return false;
        }
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform_review_evidence e
                    JOIN platform_review_task t ON t.id = ?
                      AND t.tenant_id = e.tenant_id AND t.review_case_id = ?
                      AND t.resource_type = e.resource_type AND t.resource_id = e.resource_id
                      AND t.resource_version = e.resource_version AND t.task_type = ? AND t.status = 'CLAIMED'
                    JOIN platform_task_claim c ON c.task_id = t.id
                      AND c.actor_id = ? AND c.status = 'ACTIVE'
                    WHERE e.id = ? AND e.tenant_id = ? AND e.resource_type = ? AND e.resource_id = ?
                      AND e.resource_version = ? AND e.file_id = ? AND e.scan_status = 'CLEAN'
                )
                """, Boolean.class, scope.taskId(), scope.reviewCaseId(), scope.taskType(), identity.actorId(),
                scope.evidenceId(), file.tenantId(), scope.resourceType(), scope.resourceId(),
                scope.resourceVersion(), file.id());
        return Boolean.TRUE.equals(eligible);
    }
}
