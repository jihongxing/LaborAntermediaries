package com.company.labor.foundation;

import com.company.labor.common.context.TenantContext;
import com.company.labor.common.context.UserContext;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionContextTest {

    @AfterEach
    void clear() {
        ExecutionContext.clear();
        UserContext.clear();
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreIdentityAndAlwaysClear() {
        var outer = new ExecutionContext.Identity(1, 10L, Set.of("read"));
        var inner = new ExecutionContext.Identity(2, null, Set.of());
        try (var outerScope = ExecutionContext.open(outer, "outer")) {
            assertEquals(10L, ExecutionContext.requireTenant());
            try (var innerScope = ExecutionContext.open(inner, "inner")) {
                assertEquals(2, ExecutionContext.require().actorId());
                assertFalse(TenantContext.isSet());
                assertThrows(BusinessException.class, ExecutionContext::requireTenant);
            }
            assertEquals(outer, ExecutionContext.require());
            assertEquals("outer", MDC.get("requestId"));
        }
        assertThrows(BusinessException.class, ExecutionContext::require);
        assertFalse(UserContext.isSet());
        assertFalse(TenantContext.isSet());
    }

    @Test
    void permissionsAreExplicitAndImmutable() {
        try (var scope = ExecutionContext.open(new ExecutionContext.Identity(1, 10L, Set.of("read")), "test")) {
            ExecutionContext.checkPermission("read");
            assertThrows(BusinessException.class, () -> ExecutionContext.checkPermission("admin"));
            assertThrows(UnsupportedOperationException.class, () -> ExecutionContext.require().permissions().add("write"));
        }
    }

    /** 验证通用执行上下文检查器不会把 JWT 中的平台静态权限当成授权来源。 */
    @Test
    void genericPermissionCheckRejectsStaticPlatformPermissions() {
        ExecutionContext.Identity platformIdentity = new ExecutionContext.Identity(7, null,
                Set.of("platform:organizations:review"), "PLATFORM", "session-7", "ORGANIZATION_REVIEWER");
        try (var scope = ExecutionContext.open(platformIdentity, "platform-test")) {
            assertThrows(BusinessException.class,
                    () -> ExecutionContext.checkPermission("platform:organizations:review"));
        }
    }
}
