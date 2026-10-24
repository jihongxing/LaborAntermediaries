package com.company.labor.foundation.access;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证跨组织资源授权默认拒绝、精确操作权限及独立复核限制。 */
class ResourceAccessGuardTest {
    /** 测试资源的全平台引用。 */
    private final ResourceRef resource = new ResourceRef("agreement", "agreement-1");

    /**
     * 创建模拟业务模块的权威策略。
     * @return 负责 agreement 类型的策略
     */
    private ResourceAccessPolicy policy() {
        ResourceAccessPolicy policy = mock(ResourceAccessPolicy.class);
        when(policy.resourceType()).thenReturn("agreement");
        return policy;
    }

    /** 没有身份或操作权限时拒绝，且不提前查询资源关系。 */
    @Test
    void identityAndPermissionAreRequiredBeforePolicy() {
        ResourceAccessPolicy policy = policy();
        ResourceAccessGuard guard = new ResourceAccessGuard(List.of(policy));
        assertEquals(PlatformError.UNAUTHENTICATED.code(),
                assertThrows(BusinessException.class, () -> guard.requireAccess(resource, "agreement:read")).getCode());
        try (var scope = ExecutionContext.open(new ExecutionContext.Identity(7, 11L, Set.of()), "test")) {
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class, () -> guard.requireAccess(resource, "agreement:read")).getCode());
        }
        verify(policy, never()).mayAccess(any(), any(), any());
    }

    /** 未注册类型、没有资源关系以及仅有 admin 名称均不能绕过授权。 */
    @Test
    void missingPoliciesAndRelationshipsFailClosed() {
        ResourceAccessPolicy policy = policy();
        ResourceAccessGuard guard = new ResourceAccessGuard(List.of(policy));
        try (var scope = ExecutionContext.open(
                new ExecutionContext.Identity(7, 11L, Set.of("agreement:read", "admin")), "test")) {
            assertThrows(BusinessException.class, () -> guard.requireAccess(resource, "agreement:read"));
            assertThrows(BusinessException.class,
                    () -> guard.requireAccess(new ResourceRef("unknown", "1"), "agreement:read"));
        }
    }

    /** 跨组织访问只能由模块策略明确允许，底座不会把当前身份切换成对方组织。 */
    @Test
    void explicitResourceAccessDoesNotSwitchOrganization() {
        ResourceAccessPolicy policy = policy();
        ResourceAccessGuard guard = new ResourceAccessGuard(List.of(policy));
        var identity = new ExecutionContext.Identity(7, 22L, Set.of("agreement:read"));
        when(policy.mayAccess(identity, resource, "agreement:read")).thenReturn(true, false);
        try (var scope = ExecutionContext.open(identity, "test")) {
            assertEquals(identity, guard.requireAccess(resource, "agreement:read"));
            assertEquals(22L, ExecutionContext.requireTenant());
            assertThrows(BusinessException.class, () -> guard.requireAccess(resource, "agreement:read"));
        }
        verify(policy, times(2)).mayAccess(identity, resource, "agreement:read");
    }

    /** 同类型多个策略不得使用任一放行规则，启动时直接报告配置错误。 */
    @Test
    void duplicatesAndInvalidTypesFailAtRegistration() {
        assertThrows(IllegalStateException.class, () -> new ResourceAccessGuard(List.of(policy(), policy())));
        ResourceAccessPolicy invalid = policy();
        when(invalid.resourceType()).thenReturn("unsafe/type");
        assertThrows(IllegalArgumentException.class, () -> new ResourceAccessGuard(List.of(invalid)));
    }

    /** 授权存储不可用时传播错误，不能把校验失败降级为可访问。 */
    @Test
    void policyFailuresDoNotGrantAccess() {
        ResourceAccessPolicy policy = policy();
        when(policy.mayAccess(any(), any(), any())).thenThrow(new IllegalStateException("storage unavailable"));
        ResourceAccessGuard guard = new ResourceAccessGuard(List.of(policy));
        try (var scope = ExecutionContext.open(
                new ExecutionContext.Identity(7, 11L, Set.of("agreement:read")), "test")) {
            assertThrows(IllegalStateException.class, () -> guard.requireAccess(resource, "agreement:read"));
        }
    }

    /** 当前操作者属于原经办人或需回避人员时不能完成独立复核。 */
    @Test
    void independentReviewRejectsPriorActors() {
        ResourceAccessGuard guard = new ResourceAccessGuard(List.of());
        try (var scope = ExecutionContext.open(new ExecutionContext.Identity(7, 11L, Set.of()), "test")) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(),
                    assertThrows(BusinessException.class, () -> guard.requireIndependentActor(Set.of(7L, 8L))).getCode());
            guard.requireIndependentActor(Set.of(8L));
            assertThrows(IllegalArgumentException.class, () -> guard.requireIndependentActor(Set.of(0L)));
        }
    }

    /** 资源引用只接受受限标识，禁止敏感正文或任意路径作为标识。 */
    @Test
    void resourceReferencesValidateTheirBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("../agreement", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("agreement", "secret\nbody"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("agreement", ""));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef(null, "1"));
    }
}
