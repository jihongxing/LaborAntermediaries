package com.company.labor.modules.identity;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/** 从数据库读取组织成员关系并执行首批业务门禁，不信任请求体中的组织或权限字段。 */
@Service
public class OrganizationAuthorization {
    /** 组织业务表的可信查询入口。 */
    private final JdbcTemplate jdbc;

    /** 创建组织授权服务。 */
    public OrganizationAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 要求当前操作者是组织的有效成员，并返回成员角色和组织角色。
     * @param organizationId 目标组织
     * @param allowedRoles 允许的成员角色
     * @return 成员和组织角色快照
     * @throws BusinessException 未认证、组织不匹配、成员已撤销或角色不足
     */
    public Membership membership(long organizationId, Set<MembershipRole> allowedRoles) {
        if (organizationId <= 0 || allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("Organization and member roles are required");
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() == null || identity.tenantId() != organizationId) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        Membership membership = jdbc.query("""
                SELECT m.member_role, m.status, o.organization_role, o.status AS organization_status
                FROM business_membership m
                JOIN business_organization o ON o.id = m.organization_id
                WHERE m.organization_id = ? AND m.tenant_id = ? AND m.actor_id = ?
                  AND o.tenant_id = o.id
                """, result -> result.next()
                ? new Membership(MembershipRole.valueOf(result.getString("member_role")),
                MembershipStatus.valueOf(result.getString("status")),
                OrganizationRole.valueOf(result.getString("organization_role")),
                OrganizationStatus.valueOf(result.getString("organization_status"))) : null,
                organizationId, organizationId, identity.actorId());
        if (membership == null || membership.status() != MembershipStatus.ACTIVE
                || !allowedRoles.contains(membership.memberRole())) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return membership;
    }

    /**
     * 要求组织已经通过准入审核。
     * @param organizationId 目标组织
     * @throws BusinessException 组织不存在、未通过审核或已暂停
     */
    public void requireApproved(long organizationId) {
        String status = jdbc.query("""
                SELECT status FROM business_organization WHERE id = ? AND tenant_id = ?
                """, result -> result.next() ? result.getString("status") : null,
                organizationId, organizationId);
        if (!OrganizationStatus.APPROVED.name().equals(status)) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /**
     * 验证当前 JWT 是否包含平台岗位权限。
     * @param permission 平台权限
     * @throws BusinessException 当前身份没有平台岗位权限
     */
    public void requirePlatformPermission(String permission) {
        ExecutionContext.checkPermission(permission);
    }

    /** 组织成员授权的不可变查询结果。 */
    public record Membership(MembershipRole memberRole, MembershipStatus status,
                             OrganizationRole organizationRole, OrganizationStatus organizationStatus) {
        /** 判断成员是否具有管理员能力。 */
        public boolean isAdmin() {
            return memberRole == MembershipRole.ORGANIZATION_ADMIN;
        }

        /** 返回管理员和指定角色的并集，供调用方构造权限门禁。 */
        public static Set<MembershipRole> adminOr(MembershipRole role) {
            return EnumSet.of(MembershipRole.ORGANIZATION_ADMIN, role);
        }
    }
}
