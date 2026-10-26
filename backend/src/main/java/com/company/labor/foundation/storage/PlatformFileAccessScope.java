package com.company.labor.foundation.storage;

/**
 * 平台文件读取的不可变范围；只允许由服务端根据已认领任务构造，不接受客户端直接传入。
 * @param evidenceId 已扫描证据记录编号
 * @param taskId 当前认领任务编号
 * @param reviewCaseId 稳定审核事项编号
 * @param tenantId 文件所属组织
 * @param resourceType 组织申请或需求资源类型
 * @param resourceId 被审核资源编号
 * @param resourceVersion 当前任务审核的精确资源版本
 * @param taskType 组织审核、需求审核或独立复核任务类型
 * @param purposeCode 本次平台预览的固定用途代码
 */
public record PlatformFileAccessScope(long evidenceId, long taskId, long reviewCaseId, long tenantId,
                                      String resourceType, long resourceId, long resourceVersion,
                                      String taskType, String purposeCode) {
    /**
     * 校验平台文件授权上下文中的标识和固定代码。
     * @param evidenceId 已扫描证据记录编号
     * @param taskId 当前认领任务编号
     * @param reviewCaseId 稳定审核事项编号
     * @param tenantId 文件所属组织
     * @param resourceType 组织申请或需求资源类型
     * @param resourceId 被审核资源编号
     * @param resourceVersion 当前任务审核的精确资源版本
     * @param taskType 组织审核、需求审核或独立复核任务类型
     * @param purposeCode 本次预览的固定用途码
     * @throws IllegalArgumentException 标识或代码不属于受控范围
     */
    public PlatformFileAccessScope {
        if (evidenceId <= 0 || taskId <= 0 || reviewCaseId <= 0 || tenantId <= 0 || resourceId <= 0
                || resourceVersion <= 0 || !java.util.Set.of("ORGANIZATION", "DEMAND").contains(resourceType)
                || !java.util.Set.of("ORGANIZATION_REVIEW", "DEMAND_REVIEW", "APPEAL_REVIEW").contains(taskType)
                || purposeCode == null || !purposeCode.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid platform file access scope");
        }
    }
}
