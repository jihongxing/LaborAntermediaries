package com.company.labor.modules.identity;

/** 加入组织请求的状态。 */
public enum MembershipRequestStatus {
    /** 等待组织管理员处理。 */
    PENDING,
    /** 已批准并生成成员关系。 */
    APPROVED,
    /** 已拒绝，申请人可重新发起请求。 */
    REJECTED
}
