package com.company.labor.modules.auth;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;

/** 首期受控平台岗位目录；权限明细由 Flyway 角色权限表维护。 */
public enum PlatformAuthorizationRole {
    /** 仅维护岗位授权，不含审核、复核或监察能力。 */
    AUTHORIZATION_ADMIN("AUTHORIZATION_ADMIN", true),
    /** 负责组织准入审核。 */
    ORGANIZATION_REVIEWER("ORGANIZATION_REVIEWER", false),
    /** 负责用工需求审核。 */
    DEMAND_REVIEWER("DEMAND_REVIEWER", false),
    /** 负责未参与原决定的独立复核。 */
    INDEPENDENT_REVIEWER("INDEPENDENT_REVIEWER", false),
    /** 仅负责异常任务恢复，不获得审核材料或决定能力。 */
    TASK_RECOVERY("TASK_RECOVERY", false),
    /** 独立只读监察岗位，所有授权变更均要求双人确认。 */
    INDEPENDENT_AUDITOR("INDEPENDENT_AUDITOR", true),
    /** 为既有 BP-04 服务端监督权限提供受控岗位，不增加本专项监督页面。 */
    BID_SUPERVISOR("BID_SUPERVISOR", false);

    /** 数据库中持久化的稳定岗位代码。 */
    private final String code;
    /** 是否禁止与任何 ACTIVE 组织成员关系并存。 */
    private final boolean requiresNoOrganizationMembership;

    /**
     * 声明授权岗位。
     * @param code 数据库岗位代码
     * @param requiresNoOrganizationMembership 是否禁止组织成员身份
     */
    PlatformAuthorizationRole(String code, boolean requiresNoOrganizationMembership) {
        this.code = code;
        this.requiresNoOrganizationMembership = requiresNoOrganizationMembership;
    }

    /**
     * 返回数据库稳定岗位代码。
     * @return 平台岗位代码
     */
    public String code() {
        return code;
    }

    /**
     * 判断岗位是否禁止兼任 ACTIVE 组织成员。
     * @return 管理员与监察员岗位返回 true
     */
    public boolean requiresNoOrganizationMembership() {
        return requiresNoOrganizationMembership;
    }

    /**
     * 严格解析持久化岗位代码；未知岗位不得退化为宽泛角色。
     * @param code 数据库岗位代码
     * @return 已知岗位
     * @throws BusinessException 岗位代码未知或为空
     */
    public static PlatformAuthorizationRole fromCode(String code) {
        for (PlatformAuthorizationRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new BusinessException(PlatformError.ACCESS_DENIED);
    }
}
