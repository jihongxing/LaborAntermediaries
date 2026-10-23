package com.company.labor.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * 用户上下文
 *
 * <p>使用ThreadLocal存储当前登录用户信息，方便在Service层获取。
 *
 * <p>工作流程：
 * <ol>
 *   <li>用户登录后，生成token（JWT）</li>
 *   <li>在拦截器中，从token中解析用户信息</li>
 *   <li>将用户信息放入UserContext</li>
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

    private UserContext() {
        // 私有构造函数，防止实例化
    }

    /**
     * ThreadLocal存储用户信息
     */
    private static final ThreadLocal<UserInfo> USER_HOLDER = new ThreadLocal<>();

    /**
     * 用户信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo implements Serializable {
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
     * 设置用户信息
     *
     * @param userInfo 用户信息
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
     * 清除用户信息
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
     * 判断当前用户是否是管理员
     *
     * @return true是管理员，false不是
     */
    public static boolean isAdmin() {
        String roleCode = getRoleCodeOrNull();
        return "admin".equals(roleCode);
    }
}
