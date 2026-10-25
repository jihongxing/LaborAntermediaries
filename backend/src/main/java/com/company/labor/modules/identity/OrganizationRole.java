package com.company.labor.modules.identity;

/** 本轮支持的组织业务角色；组织创建后不能在两类角色之间静默切换。 */
public enum OrganizationRole {
    /** 发布真实用工需求的一方。 */
    EMPLOYER,
    /** 承接合规外包履约的一方。 */
    LABOR_SUPPLIER
}
