package com.company.labor.modules.platform;

import java.time.Instant;
import java.util.List;

/** 审核事项历史接口的受控返回结构；不包含材料原件、联系方式或未授权内部意见。 */
public final class ReviewCaseHistoryDtos {
    /** 禁止实例化仅承载历史记录类型的命名空间。 */
    private ReviewCaseHistoryDtos() {
    }

    /**
     * 返回事项历史，并使用字符串承载数据库 bigint 标识以避免浏览器数值精度损失。
     * @param reviewCaseId 稳定审核事项编号
     * @param resourceType 组织申请或需求审核
     * @param resourceId 组织或需求资源编号
     * @param currentStatus 当前业务状态
     * @param currentVersion 当前有效业务版本
     * @param historyCoverage 历史覆盖状态；LEGACY_GAP 表示迁移前审计事件没有事项关联
     * @param versions 按版本升序排列的版本摘要
     * @param appeals 该事项已创建的复核申请及可告知申请方的结果，不含复核人员身份
     * @param events 按服务端时间排序的事项操作事件
     * @param unresolvedItems 当前需要申请方补正的公开问题
     * @param internalView 是否为受岗位权限保护的内部读取结果
     */
    public record View(String reviewCaseId, String resourceType, String resourceId, String currentStatus,
                       long currentVersion, String historyCoverage, List<Version> versions, List<Appeal> appeals,
                       List<Event> events,
                       List<String> unresolvedItems, boolean internalView) {
    }

    /**
     * 申请方可查询的独立复核状态与意见类型，不包含原件、复核正文或复核员编号。
     * @param appealCaseId 复核事项编号
     * @param resourceVersion 对应原审核版本
     * @param status 待认领、复核中、已复核或旧版本失效
     * @param outcome 已完成复核的意见类型，未完成时为空
     * @param reviewedAt 复核意见形成时间，未完成时为空
     */
    public record Appeal(String appealCaseId, long resourceVersion, String status, String outcome,
                         Instant reviewedAt) {
    }

    /**
     * 描述一个业务版本及其公开审核结论；申请方视图不会披露版本创建人账号。
     * @param resourceVersion 业务版本号
     * @param createdAt 版本记录创建时间
     * @param submittedAt 该版本首次进入审核的时间，尚未提交时为空
     * @param decisionId 该版本最新审核决定编号，供已授权申请方发起异议；尚无决定时为空
     * @param decision 该版本的审核结论，尚无结论时为空
     * @param decisionAt 审核结论时间，尚无结论时为空
     * @param publicReason 该版本面向申请方的补正或决定说明
     * @param correctionItems 该版本只追加的逐项补正事项
     */
    public record Version(long resourceVersion, Instant createdAt, Instant submittedAt,
                          String decisionId, String decision, Instant decisionAt, String publicReason,
                          List<CorrectionItem> correctionItems) {
    }

    /** 对申请方公开、并在平台内部历史中保留的版本化补正事项。 */
    public record CorrectionItem(String correctionItemId, String itemCode, String title,
                                 String description, String applicantAction, Instant createdAt) {
        /**
         * @param correctionItemId 补正事项编号
         * @param itemCode 对应审核版本内的问题代码
         * @param title 面向申请方的问题标题
         * @param description 核验发现或材料缺项说明
         * @param applicantAction 申请方可执行的补正动作
         * @param createdAt 服务端追加时间
         */
    }

    /**
     * 表示一次已有审计事件；申请方视图会隐藏操作者、岗位及内部原因码。
     * @param eventId 审计事件编号
     * @param action 固定事件动作码
     * @param resourceVersion 关联业务版本
     * @param outcome 事件结果
     * @param actorId 内部视图中的操作者账号编号
     * @param actorRole 内部视图中的当时平台岗位
     * @param reasonCode 内部视图中的结构化原因码
     * @param occurredAt 服务端记录时间
     */
    public record Event(String eventId, String action, Long resourceVersion, String outcome,
                        String actorId, String actorRole, String reasonCode, Instant occurredAt) {
    }
}
