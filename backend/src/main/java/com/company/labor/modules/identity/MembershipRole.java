package com.company.labor.modules.identity;

/** 组织内成员的最小操作角色；组织管理员同时拥有首批组织操作能力。 */
public enum MembershipRole {
    /** 管理组织成员并可执行首批组织操作。 */
    ORGANIZATION_ADMIN,
    /** 创建、编辑和提交需求草稿。 */
    DEMAND_EDITOR,
    /** 发布已审核需求。 */
    DEMAND_PUBLISHER,
    /** 代表劳务供应商提交、修改和撤回应标。 */
    SUPPLIER_BIDDER,
    /** 代表用工方在截止后执行选标和配额授予。 */
    DEMAND_SELECTOR
}
