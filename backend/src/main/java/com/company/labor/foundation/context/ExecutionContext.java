package com.company.labor.foundation.context;

import com.company.labor.common.context.UserContext;
import com.company.labor.common.exception.BusinessException;
import org.slf4j.MDC;

import java.util.Set;

public final class ExecutionContext {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private ExecutionContext() {
    }

    public record Identity(long actorId, Long tenantId, Set<String> permissions) {
        public Identity {
            if (actorId <= 0 || (tenantId != null && tenantId <= 0)) {
                throw new IllegalArgumentException("Identity identifiers must be positive");
            }
            permissions = Set.copyOf(permissions);
        }
    }

    public static Identity require() {
        Identity identity = CURRENT.get();
        if (identity == null) {
            throw new BusinessException(401, "Authentication required");
        }
        return identity;
    }

    public static long requireTenant() {
        Long tenantId = require().tenantId();
        if (tenantId == null) {
            throw new BusinessException(403, "An authenticated organization scope is required");
        }
        return tenantId;
    }

    public static void checkPermission(String permission) {
        if (!require().permissions().contains(permission)) {
            throw new BusinessException(403, "Permission denied");
        }
    }

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

    public static void clear() {
        CURRENT.remove();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
