package com.company.labor.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * 为旧代码保留的线程级用户信息桥接上下文。
 *
 * <p>存储可变用户信息引用，方便旧代码读取，不代替 ExecutionContext 的可信身份和权限。
 * ThreadLocal 不会自动传播到异步线程，调用方必须负责清理。
 *
 * <p>工作流程：
 * <ol>
 *   <li>安全链验证外部颁发的令牌并建立 ExecutionContext</li>
 *   <li>从已认证的执行上下文提取兼容用户信息</li>
 *   <li>将兼容信息放入UserContext，同时同步 TenantContext</li>
 *   <li>在Service层通过UserContext获取当前用户信息</li>
 *   <li>请求结束后，清除UserContext（防止内存泄漏）</li>
 * </ol>
 *
 * <p>使用示例：
 * <pre>
 * // 设置用户信息（在拦截器中）
 * UserInfo userInfo = new UserInfo(1001L, "张三", 2001L, "admin");
 * UserContext.setUser(userInfo);
 *
 * // 获取用户信息（在Service层）
 * Long userId = UserContext.getUserId();
 * String username = UserContext.getUsername();
 * Long tenantId = UserContext.getTenantId();
 *
 * // 清除用户信息（在拦截器的finally块中）
 * UserContext.clear();
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
public final class UserContext {

    /** 禁止实例化线程上下文工具类。 */
    private UserContext() {
        // 私有构造函数，防止实例化
    }

    /**
     * ThreadLocal存储用户信息
     */
    private static final ThreadLocal<UserInfo> USER_HOLDER = new ThreadLocal<>();

    /**
     * 兼容用户信息载体；字段可变，不应作为独立的授权凭据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo implements Serializable {
        /** 兼容用户信息的序列化版本。 */
        private static final long serialVersionUID = 1L;

        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 用户名
         */
        private String username;

        /**
         * 租户ID
         */
        private Long tenantId;

        /**
         * 角色编码（如：admin、employee、channel）
         */
        private String roleCode;
    }

    /**
     * 保存用户信息引用，先清除旧租户信息，再同步新租户；不创建可信执行上下文。
     *
     * @param userInfo 用户信息，null 表示清除用户及兼容租户信息
     * @throws IllegalArgumentException 新租户ID非正数，此时用户引用已保存但租户同步失败
     */
    public static void setUser(UserInfo userInfo) {
        if (userInfo == null) {
            clear();
            return;
        }
        TenantContext.clear();
        USER_HOLDER.set(userInfo);

        // 同时设置租户上下文
        if (userInfo.getTenantId() != null) {
            TenantContext.setTenantId(userInfo.getTenantId());
        }
    }

    /**
     * 获取用户信息
     *
     * @return 用户信息
     * @throws IllegalStateException 当前线程尚未设置用户信息
     */
    public static UserInfo getUser() {
        UserInfo userInfo = USER_HOLDER.get();
        if (userInfo == null) {
            log.error("用户信息为空，请检查拦截器是否正确设置用户信息");
            throw new IllegalStateException("用户信息为空");
        }
        return userInfo;
    }

    /**
     * 获取用户信息（不抛异常）
     *
     * @return 用户信息，可能为null
     */
    public static UserInfo getUserOrNull() {
        return USER_HOLDER.get();
    }

    /**
     * 获取用户ID
     *
     * @return 用户ID
     * @throws IllegalStateException 当前线程尚未设置用户信息
     */
    public static Long getUserId() {
        return getUser().getUserId();
    }

    /**
     * 获取用户ID（不抛异常）
     *
     * @return 用户ID，可能为null
     */
    public static Long getUserIdOrNull() {
        UserInfo userInfo = getUserOrNull();
        return userInfo != null ? userInfo.getUserId() : null;
    }

    /**
     * 获取用户名
     *
     * @return 用户名
     * @throws IllegalStateException 当前线程尚未设置用户信息
     */
    public static String getUsername() {
        return getUser().getUsername();
    }

    /**
     * 获取用户名（不抛异常）
     *
     * @return 用户名，可能为null
     */
    public static String getUsernameOrNull() {
        UserInfo userInfo = getUserOrNull();
        return userInfo != null ? userInfo.getUsername() : null;
    }

    /**
     * 获取租户ID
     *
     * @return 租户ID
     * @throws IllegalStateException 当前线程尚未设置用户信息
     */
    public static Long getTenantId() {
        return getUser().getTenantId();
    }

    /**
     * 获取租户ID（不抛异常）
     *
     * @return 租户ID，可能为null
     */
    public static Long getTenantIdOrNull() {
        UserInfo userInfo = getUserOrNull();
        return userInfo != null ? userInfo.getTenantId() : null;
    }

    /**
     * 获取角色编码
     *
     * @return 角色编码
     * @throws IllegalStateException 当前线程尚未设置用户信息
     */
    public static String getRoleCode() {
        return getUser().getRoleCode();
    }

    /**
     * 获取角色编码（不抛异常）
     *
     * @return 角色编码，可能为null
     */
    public static String getRoleCodeOrNull() {
        UserInfo userInfo = getUserOrNull();
        return userInfo != null ? userInfo.getRoleCode() : null;
    }

    /**
     * 清除当前线程的兼容用户及租户信息，不清除 ExecutionContext 或 MDC。
     */
    public static void clear() {
        UserInfo userInfo = USER_HOLDER.get();
        USER_HOLDER.remove();
        log.debug("清除用户信息：userId={}", userInfo != null ? userInfo.getUserId() : null);

        // 同时清除租户上下文
        TenantContext.clear();
    }

    /**
     * 判断是否已设置用户信息
     *
     * @return true已设置，false未设置
     */
    public static boolean isSet() {
        return USER_HOLDER.get() != null;
    }

    /**
     * 检查兼容角色字段是否等于 admin，不替代可信上下文的权限校验。
     *
     * @return true是管理员，false不是
     */
    public static boolean isAdmin() {
        String roleCode = getRoleCodeOrNull();
        return "admin".equals(roleCode);
    }
}
