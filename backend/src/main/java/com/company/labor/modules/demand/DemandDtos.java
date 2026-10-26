package com.company.labor.modules.demand;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 首批需求接口数据结构；金额或价格条件按字符串/精确 decimal 表示，不执行收费。 */
public final class DemandDtos {
    /** DTO 容器不允许实例化。 */
    private DemandDtos() {
    }

    /** 创建或更新需求的完整版本内容。 */
    public record UpsertRequest(
            @NotBlank @Size(max = 1000) String relatedPartyRelation,
            @NotBlank @Size(max = 4000) String serviceScope,
            @NotBlank @Size(max = 1000) String location,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotBlank @Size(max = 200) String title,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotBlank @Size(max = 64) String quantityUnit,
            @NotBlank @Size(max = 2000) String target,
            @NotBlank @Size(max = 64) String pricingMode,
            @NotBlank @Size(max = 1000) String measurementBasis,
            @NotBlank @Size(max = 200) String settlementCycle,
            @NotNull Instant bidDeadline,
            @NotNull Instant confirmationDeadline,
            @NotBlank @Size(max = 2000) String selectionRule) {
        /**
         * 确保数量精确且在数据库允许的整数位范围内。
         * @param relatedPartyRelation 实际服务、签约与付款主体关系
         * @param serviceScope 可采购服务范围
         * @param location 服务地点
         * @param startsAt 服务开始时间
         * @param endsAt 服务结束时间
         * @param title 需求或标项名称
         * @param quantity 需求数量
         * @param quantityUnit 数量单位
         * @param target 目标或供应商响应条件
         * @param pricingMode 报价模式标识
         * @param measurementBasis 计量与确认口径
         * @param settlementCycle 结算周期说明
         * @param bidDeadline 应标截止时间
         * @param confirmationDeadline 中标供应商确认截止时间
         * @param selectionRule 选标及配额规则说明
         */
        public UpsertRequest {
            if (quantity != null && quantity.precision() > 20) {
                throw new IllegalArgumentException("Quantity precision exceeds the supported range");
            }
        }
    }

    /** 平台对需求的审核决定。 */
    public record ReviewRequest(@NotNull Decision decision, @Size(max = 1000) String reason,
                                @Size(max = 20) List<@jakarta.validation.Valid CorrectionItemRequest> correctionItems) {
        /** 保留通过/拒绝调用方的简洁构造方式；补正决定由领域服务强制要求逐项清单。 */
        public ReviewRequest(Decision decision, String reason) {
            this(decision, reason, List.of());
        }

        /** 平台审核动作。 */
        public enum Decision {
            /** 审核通过并允许发布人显式发布。 */
            APPROVE,
            /** 退回补正。 */
            NEEDS_CORRECTION,
            /** 拒绝本次需求。 */
            REJECT
        }
    }

    /** 对申请方公开、并绑定当前需求版本的问题与补正动作。 */
    public record CorrectionItemRequest(
            @NotBlank @Size(max = 64) String itemCode,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 1000) String description,
            @NotBlank @Size(max = 1000) String applicantAction) {
        /**
         * @param itemCode 本次审核版本内唯一的问题代码
         * @param title 申请方可理解的问题标题
         * @param description 核验发现或需求缺项说明
         * @param applicantAction 申请方可以执行的补正动作
         */
    }

    /** 需求审核员认领当前任务后可见的需求字段白名单。 */
    public record ReviewSubject(String demandId, long revision, String status, String title,
                                BigDecimal quantity, String quantityUnit, Instant startsAt, Instant endsAt,
                                Instant bidDeadline, String serviceScope, String location,
                                String relatedPartyRelation, String target, String pricingMode,
                                String measurementBasis, String settlementCycle, Instant confirmationDeadline,
                                String selectionRule, String reviewReason) {
        /**
         * 返回已认领需求版本供审核使用；不含组织联系人、应标、供应商资料或报价记录。
         * @param demandId 需求编号，以十进制字符串避免浏览器精度损失
         * @param revision 当前审核版本
         * @param status 当前需求状态
         * @param title 需求标题
         * @param quantity 精确需求数量
         * @param quantityUnit 数量单位
         * @param startsAt 服务开始时间
         * @param endsAt 服务结束时间
         * @param bidDeadline 应标截止时间
         * @param serviceScope 服务范围
         * @param location 服务地点
         * @param relatedPartyRelation 实际服务、签约及付款主体关系
         * @param target 用工目标与供应商响应条件
         * @param pricingMode 报价模式约定，不含供应商实际报价
         * @param measurementBasis 计量与确认口径
         * @param settlementCycle 结算周期约定
         * @param confirmationDeadline 供应商确认截止时间
         * @param selectionRule 用工方选标与配额规则说明
         * @param reviewReason 当前申请方可见审核说明
         */
        public ReviewSubject {
        }
    }

    /** 已审核或开放的需求摘要，不包含联系人、附件和供应商报价。 */
    public record View(long id, long organizationId, long revision, DemandStatus status,
                       String title, BigDecimal quantity, String quantityUnit, Instant startsAt,
                       Instant endsAt, Instant bidDeadline, String serviceScope, String location,
                       String relatedPartyRelation, String target, String pricingMode,
                       String measurementBasis, String settlementCycle, Instant confirmationDeadline,
                       String selectionRule,
                       String reviewReason) {
        /**
         * 返回精确 decimal 数量并由 API 层序列化。
         * @param id 需求标识
         * @param organizationId 需求所属组织
         * @param revision 当前业务版本
         * @param status 需求状态
         * @param title 需求标题
         * @param quantity 需求数量
         * @param quantityUnit 数量单位
         * @param startsAt 服务开始时间
         * @param endsAt 服务结束时间
         * @param bidDeadline 应标截止时间
         * @param serviceScope 服务范围
         * @param location 服务地点
         * @param relatedPartyRelation 相关主体关系
         * @param target 目标与响应条件
         * @param pricingMode 报价模式
         * @param measurementBasis 计量口径
         * @param settlementCycle 结算周期
         * @param confirmationDeadline 供应商确认截止时间
         * @param selectionRule 选标规则
         * @param reviewReason 当前审核理由
         */
        public View {
        }
    }
}
