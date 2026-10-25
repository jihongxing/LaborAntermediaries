package com.company.labor.modules.identity;

/** 组织申请和运营状态；审核通过与需求审核是两个独立门槛。 */
public enum OrganizationStatus {
    /** 已保存但尚未提交的平台申请。 */
    DRAFT,
    /** 已提交，等待平台审核。 */
    PENDING_REVIEW,
    /** 组织准入通过，可进入需求门禁。 */
    APPROVED,
    /** 平台指出缺项，申请人可补正后重新提交。 */
    NEEDS_CORRECTION,
    /** 组织准入未通过。 */
    REJECTED,
    /** 暂停新增业务能力，但不删除历史事实。 */
    SUSPENDED
}
