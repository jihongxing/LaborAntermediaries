package com.company.labor.modules.identity;

/** 成员关系状态；撤权后旧成员关系不能继续执行新业务。 */
public enum MembershipStatus {
    /** 当前可以按成员角色执行操作。 */
    ACTIVE,
    /** 成员关系已撤销，仅保留历史审计。 */
    REVOKED
}
