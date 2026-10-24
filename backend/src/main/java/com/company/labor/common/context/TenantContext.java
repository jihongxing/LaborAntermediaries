package com.company.labor.common.context;

import lombok.extern.slf4j.Slf4j;

/**
 * 为旧代码保留的线程级租户上下文。
 *
 * <p>仅提供兼容读取，不构成认证或数据隔离依据；MyBatis 隔离读取 ExecutionContext。
 * ThreadLocal 不会自动传播到异步线程。
 *
 * <p>工作流程：
 * <ol>
 *   <li>安全链校验令牌并建立可信的 ExecutionContext</li>
 *   <li>UserContext 桥接用户信息时同步设置此兼容上下文</li>
 *   <li>旧代码读取租户ID；组织授权仍以可信执行上下文为准</li>
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

    /** 禁止实例化线程上下文工具类。 */
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
     * @param tenantId 正数租户ID，null 表示清除当前值
     * @throws IllegalArgumentException 租户ID不是正数
     */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            clear();
            return;
        }
        if (tenantId <= 0) {
            throw new IllegalArgumentException("Tenant identifier must be positive");
        }
        TENANT_ID_HOLDER.set(tenantId);
        log.debug("设置租户ID：{}", tenantId);
    }

    /**
     * 获取租户ID
     *
     * @return 租户ID
     * @throws IllegalStateException 当前线程尚未设置租户ID
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
