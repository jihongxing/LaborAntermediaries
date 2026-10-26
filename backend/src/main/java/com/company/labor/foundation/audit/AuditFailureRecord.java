package com.company.labor.foundation.audit;

import java.time.Instant;
import java.util.UUID;

/** 审计数据库写入失败时保存到独立本地持久化通道的最小事件。 */
public record AuditFailureRecord(
        String failureId,
        Instant failedAt,
        Long actorId,
        String actorType,
        Long tenantId,
        String action,
        String resourceType,
        String resourceId,
        String attemptedOutcome,
        String requestId,
        String correlationId,
        AuditRecorder.AuditDetails details,
        String exceptionType) {

    /**
     * 校验故障事件结构，避免本地通道保存任意请求正文。
     * @param failureId 故障事件 UUID
     * @param failedAt 数据库追加失败的时间
     * @param actorId 原操作账号标识，匿名事件为空
     * @param actorType 原操作身份类型
     * @param tenantId 原操作组织标识，可为空
     * @param action 原审计操作码
     * @param resourceType 原资源类型
     * @param resourceId 原资源标识
     * @param attemptedOutcome 原尝试结果
     * @param requestId 服务端请求标识
     * @param correlationId 服务端关联标识
     * @param details 原审计关联信息
     * @param exceptionType 异常类短名称
     * @throws IllegalArgumentException 标识不符合审计字段约束
     * @throws NullPointerException 必需字段为空
     */
    public AuditFailureRecord {
        if (failureId == null || !failureId.matches("[0-9a-f-]{36}") || failedAt == null
                || actorType == null || action == null || resourceType == null || resourceId == null
                || attemptedOutcome == null || correlationId == null || details == null || exceptionType == null) {
            throw new IllegalArgumentException("Invalid audit failure record");
        }
    }

    /**
     * 从一次审计追加失败创建稳定、可恢复的记录。
     * @param actorId 操作账号
     * @param actorType 身份类型
     * @param tenantId 组织范围
     * @param action 操作码
     * @param resourceType 资源类型
     * @param resourceId 资源标识
     * @param attemptedOutcome 原尝试结果
     * @param requestId 请求标识
     * @param correlationId 关联标识
     * @param details 事项与访问上下文
     * @param exception 异常，仅提取异常类型，不保存异常消息
     * @return 可写入独立本地通道的审计故障记录
     */
    public static AuditFailureRecord create(Long actorId, String actorType, Long tenantId, String action,
                                            String resourceType, String resourceId, String attemptedOutcome,
                                            String requestId, String correlationId,
                                            AuditRecorder.AuditDetails details, RuntimeException exception) {
        return new AuditFailureRecord(UUID.randomUUID().toString(), Instant.now(), actorId, actorType, tenantId,
                action, resourceType, resourceId, attemptedOutcome, requestId, correlationId, details,
                exception.getClass().getSimpleName());
    }
}
