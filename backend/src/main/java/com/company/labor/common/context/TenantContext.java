package com.company.labor.common.context;

import lombok.extern.slf4j.Slf4j;

/**
 * 租户上下文
 *
 * <p>使用ThreadLocal存储当前请求的租户ID，保证多租户数据隔离。
 *
 * <p>工作流程：
 * <ol>
 *   <li>用户登录后，从token中提取租户ID</li>
 *   <li>在拦截器或过滤器中，将租户ID放入TenantContext</li>
 *   <li>MyBatis Plus租户插件自动在SQL中注入WHERE tenant_id = ?</li>
 *   <li>请求结束后，清除TenantContext（防止内存泄漏）</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>
 * // 设置租户ID（在拦截器中）
 * TenantContext.setTenantId(1001L);
 *
 * // 获取租户ID（在Service层）
 * Long tenantId = TenantContext.getTenantId();
 *
 * // 清除租户ID（在拦截器的finally块中）
 * TenantContext.clear();
 * </pre>
 *
 * <p>安全要求：
 * <ul>
 *   <li>必须在拦截器中设置租户ID，不能在业务代码中设置</li>
 *   <li>必须在请求结束后清除租户ID，防止内存泄漏</li>
 *   <li>跨租户查询必须经过审批，不能绕过租户插件</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
public final class TenantContext {

    private TenantContext() {
        // 私有构造函数，防止实例化
    }

    /**
     * ThreadLocal存储租户ID
     */
    private static final ThreadLocal<Long> TENANT_ID_HOLDER = new ThreadLocal<>();

    /**
     * 设置租户ID
     *
     * @param tenantId 租户ID
     */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            log.warn("设置租户ID为null，可能导致数据隔离失败");
            return;
        }
        TENANT_ID_HOLDER.set(tenantId);
        log.debug("设置租户ID：{}", tenantId);
    }

    /**
     * 获取租户ID
     *
     * @return 租户ID
     */
    public static Long getTenantId() {
        Long tenantId = TENANT_ID_HOLDER.get();
        if (tenantId == null) {
            log.error("租户ID为空，请检查拦截器是否正确设置租户ID");
            throw new IllegalStateException("租户ID为空");
        }
        return tenantId;
    }

    /**
     * 获取租户ID（不抛异常）
     *
     * @return 租户ID，可能为null
     */
    public static Long getTenantIdOrNull() {
        return TENANT_ID_HOLDER.get();
    }

    /**
     * 清除租户ID
     */
    public static void clear() {
        Long tenantId = TENANT_ID_HOLDER.get();
        TENANT_ID_HOLDER.remove();
        log.debug("清除租户ID：{}", tenantId);
    }

    /**
     * 判断是否已设置租户ID
     *
     * @return true已设置，false未设置
     */
    public static boolean isSet() {
        return TENANT_ID_HOLDER.get() != null;
    }
}
