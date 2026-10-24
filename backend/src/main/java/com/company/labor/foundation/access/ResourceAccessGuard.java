package com.company.labor.foundation.access;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.context.ExecutionContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将操作权限与资源级授权串联，支持明确授权的跨组织协作，不放宽全表隔离。
 * 每个资源类型只有一个权威策略，未知类型默认拒绝，平台身份也无隐含全局放行。
 */
@Service
public class ResourceAccessGuard {
    /** 按资源类型索引的不可变策略注册表。 */
    private final Map<String, ResourceAccessPolicy> policies;

    /**
     * 注册模块策略，重复类型启动失败，避免多个策略以任一允许的方式扩大权限。
     * @param policies 业务模块提供的策略集合，空集合表示没有资源可访问
     * @throws IllegalStateException 同一类型注册多个策略
     * @throws IllegalArgumentException 策略类型不符合资源标识规范
     */
    public ResourceAccessGuard(List<ResourceAccessPolicy> policies) {
        Map<String, ResourceAccessPolicy> registered = new HashMap<>();
        for (ResourceAccessPolicy policy : policies) {
            String type = new ResourceRef(policy.resourceType(), "validation").type();
            if (registered.putIfAbsent(type, policy) != null) {
                throw new IllegalStateException("Duplicate resource access policy");
            }
        }
        this.policies = Map.copyOf(registered);
    }

    /**
     * 同时验证身份、精确操作权限和当前资源关系，不提供切换组织绕过隔离的能力。
     * @param resource 待访问资源
     * @param permission 业务模块要求的精确权限标识
     * @return 已通过所有校验的当前身份
     * @throws BusinessException 未认证或缺少操作权限、策略、资源关系
     * @throws IllegalArgumentException 权限标识不合法
     */
    public ExecutionContext.Identity requireAccess(ResourceRef resource, String permission) {
        Objects.requireNonNull(resource, "resource");
        if (permission == null || !permission.matches("[a-zA-Z0-9:._-]{1,100}")) {
            throw new IllegalArgumentException("Invalid resource permission");
        }
        ExecutionContext.checkPermission(permission);
        ExecutionContext.Identity identity = ExecutionContext.require();
        ResourceAccessPolicy policy = policies.get(resource.type());
        if (policy == null || !policy.mayAccess(identity, resource, permission)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return identity;
    }

    /**
     * 校验当前操作者不在需回避人员集合中；不代替资源授权或完整的利益冲突审查。
     * 业务须从原始处理记录加载集合，并在同一用例中先做资源授权。
     * @param excludedActors 经办人、初审人等必须回避的正数操作者标识
     * @throws BusinessException 未认证或当前操作者需要回避
     * @throws IllegalArgumentException 集合含非正数标识
     */
    public void requireIndependentActor(Set<Long> excludedActors) {
        Set<Long> excluded = Set.copyOf(excludedActors);
        if (excluded.stream().anyMatch(actor -> actor <= 0)) {
            throw new IllegalArgumentException("Excluded actor identifiers must be positive");
        }
        if (excluded.contains(ExecutionContext.require().actorId())) {
            throw new BusinessException(PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
    }
}
