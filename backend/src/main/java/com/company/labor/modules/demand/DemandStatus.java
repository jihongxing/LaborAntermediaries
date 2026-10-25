package com.company.labor.modules.demand;

/** 首批需求状态；组织准入、需求审核和应标开放相互独立。 */
public enum DemandStatus {
    /** 用工方可编辑的需求草稿。 */
    DRAFT,
    /** 已提交平台审核。 */
    PENDING_REVIEW,
    /** 退回补正，尚不可发布。 */
    NEEDS_CORRECTION,
    /** 审核拒绝，尚不可发布。 */
    REJECTED,
    /** 已审核通过，等待有权成员发布。 */
    APPROVED_PENDING_PUBLISH,
    /** 已发布并开放给合格劳务公司读取。 */
    OPEN_FOR_BID,
    /** 被受控暂停，不接受新的应标。 */
    SUSPENDED,
    /** 需求已结束或撤回。 */
    CLOSED
}
