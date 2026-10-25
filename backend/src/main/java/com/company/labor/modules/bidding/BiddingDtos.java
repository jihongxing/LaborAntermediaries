package com.company.labor.modules.bidding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** BP-04 应标、选标和确认接口的结构化数据，不承载履约、结算或支付字段。 */
public final class BiddingDtos {
    /** DTO 容器不允许实例化。 */
    private BiddingDtos() {
    }

    /** 供应商提交或追加应标版本；报价使用精确 decimal。 */
    public record SubmitRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal committedQuantity,
            @NotBlank @Size(max = 4000) String commitment,
            @NotBlank @Size(max = 4000) String deviationItems,
            @NotBlank @Size(max = 8000) String proposal,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal quotedAmount,
            @NotBlank @Size(min = 3, max = 3) String quoteCurrency,
            @NotBlank @Size(max = 64) String quoteUnit,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal taxRate,
            @NotBlank @Size(max = 200) String paymentTerm,
            @NotBlank @Size(max = 2000) String paymentArrangement,
            @NotNull Boolean acceptedTerms) {
        /**
         * 保证供应商明确接受本次应标规则，不能通过缺省值形成默示确认。
         * @param committedQuantity 可承诺数量
         * @param commitment 承诺内容
         * @param deviationItems 偏离项，没有偏离时填写“无”
         * @param proposal 服务方案
         * @param quotedAmount 报价金额
         * @param quoteCurrency 三位货币代码
         * @param quoteUnit 报价单位
         * @param taxRate 税率百分比
         * @param paymentTerm 账期
         * @param paymentArrangement 付款安排
         * @param acceptedTerms 是否明确接受需求条款
         */
        public SubmitRequest {
            if (taxRate != null && taxRate.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Tax rate must be between 0 and 100");
            }
        }
    }

    /** 用工方单次选标中的一条配额授予。 */
    public record Allocation(
            @NotNull Long bidId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {
        /**
         * 声明配额数量。
         * @param bidId 应标标识
         * @param quantity 本次授予数量
         */
        public Allocation {
        }
    }

    /** 用工方在截止后发起的多供应商配额授予。 */
    public record AwardRequest(
            @NotEmpty @Size(max = 100) List<@Valid Allocation> allocations,
            @NotBlank @Size(max = 32) String allocationMode,
            @Size(max = 32) String roundingMode) {
        /**
         * 复制列表，避免控制器之后修改请求影响事务审计摘要。
         * @param allocations 配额明细
         * @param allocationMode ABSOLUTE 或 PROPORTIONAL
         * @param roundingMode 比例配额取整规则
         */
        public AwardRequest {
            allocations = allocations == null ? null : List.copyOf(allocations);
        }
    }

    /** 供应商对单个授予的明确确认或拒绝。 */
    public record ConfirmationRequest(@NotNull Boolean accepted, @Size(max = 1000) String reason) {
        /**
         * 说明确认决定。
         * @param accepted 是否接受全部授予
         * @param reason 拒绝理由或补充说明
         */
        public ConfirmationRequest {
        }
    }

    /** 平台监督记录的最小动作和证据引用。 */
    public record SupervisionRequest(@NotBlank @Size(max = 64) String action,
                                     @NotBlank @Size(max = 200) String evidenceReference) {
        /**
         * 平台不在此请求中提交交易决定，只登记监督事实。
         * @param action 监督动作标识
         * @param evidenceReference 证据系统中的引用
         */
        public SupervisionRequest {
        }
    }

    /** 供应商自己的应标或用工方收到的应标视图。 */
    public record BidView(long id, long demandId, long supplierOrganizationId, long revision,
                          BidStatus status, BigDecimal committedQuantity, String commitment,
                          String deviationItems, String proposal, BigDecimal quotedAmount,
                          String quoteCurrency, String quoteUnit, BigDecimal taxRate,
                          String paymentTerm, String paymentArrangement, Instant createdAt) {
        /**
         * 应标视图不包含其他供应商的任何记录，报价字段只在供应商本人或用工方授权查询中出现。
         * @param id 应标标识
         * @param demandId 需求标识
         * @param supplierOrganizationId 供应商组织
         * @param revision 当前应标版本
         * @param status 应标状态
         * @param committedQuantity 承诺数量
         * @param commitment 承诺内容
         * @param deviationItems 偏离项
         * @param proposal 服务方案
         * @param quotedAmount 报价金额
         * @param quoteCurrency 货币
         * @param quoteUnit 报价单位
         * @param taxRate 税率
         * @param paymentTerm 账期
         * @param paymentArrangement 付款安排
         * @param createdAt 当前版本时间
         */
        public BidView {
        }
    }

    /** 选标授予及供应商确认状态。 */
    public record AwardView(long id, long demandId, long bidId, long supplierOrganizationId,
                            long bidRevision, BigDecimal awardedQuantity, Instant confirmationDeadline,
                            AwardStatus status, Instant decidedAt, Instant confirmedAt,
                            ConfirmationTerms terms) {
        /**
         * 授予视图只返回与当前组织有关的记录。
         * @param id 授予标识
         * @param demandId 需求标识
         * @param bidId 应标标识
         * @param supplierOrganizationId 供应商组织
         * @param bidRevision 授予所依据的应标版本
         * @param awardedQuantity 授予数量
         * @param confirmationDeadline 确认截止时间
         * @param status 授予状态
         * @param decidedAt 用工方决策时间
         * @param confirmedAt 供应商确认时间
         * @param terms 授予所依据的需求及应标完整条件
         */
        public AwardView {
        }
    }

    /** 供应商确认前向双方披露的完整需求版本和应标版本条件。 */
    public record ConfirmationTerms(long demandRevision, String demandTitle, String serviceScope,
                                    String location, Instant startsAt, Instant endsAt,
                                    BigDecimal demandQuantity, String quantityUnit, String target,
                                    String pricingMode, String measurementBasis, String settlementCycle,
                                    Instant bidDeadline, Instant confirmationDeadline, String selectionRule,
                                    BigDecimal committedQuantity, String commitment, String deviationItems,
                                    String proposal, BigDecimal quotedAmount, String quoteCurrency,
                                    String quoteUnit, BigDecimal taxRate, String paymentTerm,
                                    String paymentArrangement) {
        /**
         * 声明确认时交易双方使用的需求和应标条件快照。
         * @param demandRevision 需求版本
         * @param demandTitle 需求标题
         * @param serviceScope 服务范围
         * @param location 服务地点
         * @param startsAt 服务开始时间
         * @param endsAt 服务结束时间
         * @param demandQuantity 需求总量
         * @param quantityUnit 数量单位
         * @param target 需求目标
         * @param pricingMode 报价模式
         * @param measurementBasis 计量口径
         * @param settlementCycle 结算周期
         * @param bidDeadline 应标截止时间
         * @param confirmationDeadline 供应商确认截止时间
         * @param selectionRule 选标规则
         * @param committedQuantity 供应商承诺量
         * @param commitment 供应商承诺内容
         * @param deviationItems 允许的偏离
         * @param proposal 供应商服务方案
         * @param quotedAmount 报价金额
         * @param quoteCurrency 报价币种
         * @param quoteUnit 报价单位
         * @param taxRate 报价税率
         * @param paymentTerm 账期
         * @param paymentArrangement 付款安排
         */
        public ConfirmationTerms {
        }
    }

    /** 只读的 BP-05 确认快照交接结果。 */
    public record SnapshotView(long id, long awardId, long demandId, long employerOrganizationId,
                               long supplierOrganizationId, long bidId, long bidRevision,
                               BigDecimal awardedQuantity, String status, long confirmedBy,
                               Instant confirmedAt, String snapshotPayload) {
        /**
         * 快照只代表双方确认后的交接契约，不代表已创建履约项目。
         * @param id 快照标识
         * @param awardId 授予标识
         * @param demandId 需求标识
         * @param employerOrganizationId 用工方组织
         * @param supplierOrganizationId 供应商组织
         * @param bidId 应标标识
         * @param bidRevision 应标版本
         * @param awardedQuantity 已确认数量
         * @param status 快照状态
         * @param confirmedBy 确认供应商成员
         * @param confirmedAt 确认时间
         * @param snapshotPayload 不可变需求、应标和授予条件 JSON
         */
        public SnapshotView {
        }
    }

    /** 应标生命周期状态。 */
    public enum BidStatus {
        /** 截止前可被修改或撤回。 */
        ACTIVE,
        /** 供应商主动撤回，允许在需求暂停期间发生。 */
        WITHDRAWN,
        /** 需求撤回或关闭后不可再操作。 */
        CLOSED
    }

    /** 选标授予生命周期状态。 */
    public enum AwardStatus {
        /** 等待供应商明确决定。 */
        PENDING_CONFIRMATION,
        /** 供应商已接受并已交接 BP-05 快照。 */
        CONFIRMED,
        /** 供应商明确拒绝，配额已释放。 */
        REJECTED,
        /** 到期未确认，配额已释放。 */
        EXPIRED
    }
}
