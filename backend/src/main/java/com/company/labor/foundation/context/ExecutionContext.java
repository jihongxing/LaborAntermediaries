package com.company.labor.foundation.context;

import com.company.labor.common.context.UserContext;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import org.slf4j.MDC;

import java.util.Set;

/** 保存当前线程的可信执行身份；跨线程执行必须显式打开作用域，不能依赖自动传递。 */
public final class ExecutionContext {
    /** 当前线程的身份；作用域关闭或请求清理时必须移除，避免线程复用造成身份泄漏。 */
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    /** 工具类不允许实例化。 */
    private ExecutionContext() {
    }

    /**
     * 已通过认证的不可变身份快照。
     * @param actorId 正数操作者标识
     * @param tenantId 正数组织标识，可为空，空值不允许访问组织业务
     * @param permissions 非空权限集合，构造时复制以防外部修改
     */
    public record Identity(long actorId, Long tenantId, Set<String> permissions) {
        /**
         * 校验标识并防御性复制权限。
         * @param actorId 操作者标识
         * @param tenantId 可空组织标识
         * @param permissions 权限集合
         * @throws IllegalArgumentException 标识不是正数
         * @throws NullPointerException 权限集合或其中元素为空
         */
        public Identity {
            if (actorId <= 0 || (tenantId != null && tenantId <= 0)) {
                throw new IllegalArgumentException("Identity identifiers must be positive");
            }
            permissions = Set.copyOf(permissions);
        }
    }

    /**
     * 获取当前身份，不允许匿名执行。
     * @return 当前线程的身份
     * @throws BusinessException 没有身份时以 HTTP 401 拒绝，业务码使用独立错误契约
     */
    public static Identity require() {
        Identity identity = CURRENT.get();
        if (identity == null) {
            throw new BusinessException(PlatformError.UNAUTHENTICATED);
        }
        return identity;
    }

    /**
     * 获取明确的组织范围，禁止无组织身份访问组织数据。
     * @return 当前组织标识
     * @throws BusinessException 未认证为 HTTP 401，没有组织范围为 HTTP 403
     */
    public static long requireTenant() {
        Long tenantId = require().tenantId();
        if (tenantId == null) {
            throw new BusinessException(PlatformError.ORGANIZATION_REQUIRED);
        }
        return tenantId;
    }

    /**
     * 检查当前身份是否包含指定权限。
     * @param permission 必须具备的完整权限字符串
     * @throws BusinessException 未认证为 HTTP 401，缺少权限为 HTTP 403
     */
    public static void checkPermission(String permission) {
        if (!require().permissions().contains(permission)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 打开线程作用域，同时设置兼容用户上下文与日志请求标识。
     * 调用方必须使用 try-with-resources 关闭，以恢复外层身份及请求标识。
     * @param identity 要绑定的可信非空身份
     * @param requestId 请求标识，为空时移除当前日志请求标识
     * @return 负责恢复上下文的作用域
     */
    public static Scope open(Identity identity, String requestId) {
        Identity previous = CURRENT.get();
        String previousRequest = MDC.get("requestId");
        CURRENT.set(identity);
        UserContext.setUser(new UserContext.UserInfo(identity.actorId(), null, identity.tenantId(), null));
        if (requestId == null) {
            MDC.remove("requestId");
        } else {
            MDC.put("requestId", requestId);
        }
        return () -> {
            // 先清理内层身份，再恢复外层上下文，保证嵌套作用域不会串用组织身份。
            clear();
            UserContext.clear();
            if (previous != null) {
                CURRENT.set(previous);
                UserContext.setUser(new UserContext.UserInfo(previous.actorId(), null, previous.tenantId(), null));
            }
            if (previousRequest == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequest);
            }
        };
    }

    /** 只清理基础执行身份；兼容用户上下文和 MDC 由作用域或请求过滤器分别清理。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 可由 try-with-resources 管理的上下文作用域，不声明受检关闭异常。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /** 释放当前作用域，并恢复打开前的身份和日志请求标识。 */
        @Override
        void close();
    }
}
