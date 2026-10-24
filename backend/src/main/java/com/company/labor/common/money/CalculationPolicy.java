package com.company.labor.common.money;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * 由业务用例显式选择并留存的计算精度策略，不含比例、税率、收费档次或默认舍入模式。
 * 同一标识和版本对应的语义不得变更；本值对象不提供策略注册、审批或真实性证明。
 * @param key 稳定策略标识
 * @param version 正数版本
 * @param divisionScale 除法保留的位数，零到 32；需按结算数量上限验证误差预算
 * @param divisionRounding 除法舍入模式
 * @param settlementRounding 最终转换为币种最小单位的舍入模式
 */
public record CalculationPolicy(String key, int version, int divisionScale,
                                RoundingMode divisionRounding, RoundingMode settlementRounding) {
    /**
     * 校验显式策略，不根据空值或未填写的配置生成默认规则。
     * @param key 最多 100 位稳定标识
     * @param version 正数策略版本
     * @param divisionScale 除法保留的小数位数
     * @param divisionRounding 显式除法舍入模式
     * @param settlementRounding 显式结算舍入模式
     * @throws NullPointerException 任一舍入模式为空
     * @throws IllegalArgumentException 标识、版本或位数不合法
     */
    public CalculationPolicy {
        if (key == null || !key.matches("[a-zA-Z0-9:._-]{1,100}") || version <= 0
                || divisionScale < 0 || divisionScale > 32) {
            throw new IllegalArgumentException("Invalid calculation policy");
        }
        Objects.requireNonNull(divisionRounding, "divisionRounding");
        Objects.requireNonNull(settlementRounding, "settlementRounding");
    }

    /**
     * 在调用方确定的结算节点将计算值转换为最小货币单位，并返回可留存的转换封套。
     * 不自动汇总、判定含税关系、确认账单或批准支付，原输入与汇总范围仍须由业务记录。
     * @param calculated 已按业务约定范围汇总的计算值
     * @return 原值、最终金额与所用策略
     * @throws NullPointerException 计算值为空
     * @throws ArithmeticException 策略禁止必要的舍入
     * @throws IllegalArgumentException 金额超出 Money 技术容量
     */
    public Settlement settle(CalculatedAmount calculated) {
        Objects.requireNonNull(calculated, "calculated");
        Money amount = new Money(calculated.value().setScale(
                calculated.currency().getDefaultFractionDigits(), settlementRounding), calculated.currency());
        return new Settlement(calculated, amount, this);
    }

    /**
     * 一次计算值转结算金额的不可变封套，不是业务批准或付款回执。
     * @param calculated 舍入前的计算金额
     * @param amount 按策略得到的最终金额
     * @param policy 此次采用的策略标识、版本和舍入参数
     */
    public record Settlement(CalculatedAmount calculated, Money amount, CalculationPolicy policy) {
        /**
         * 校验封套内部一致性，防止保存与策略不匹配的最终金额。
         * @param calculated 原计算值
         * @param amount 最终金额
         * @param policy 显式策略
         * @throws NullPointerException 任一组件为空
         * @throws ArithmeticException 策略禁止原值所需的舍入
         * @throws IllegalArgumentException 币种或最终值与策略不一致
         */
        public Settlement {
            Objects.requireNonNull(calculated, "calculated");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(policy, "policy");
            if (!calculated.currency().equals(amount.currency())
                    || calculated.value().setScale(amount.currency().getDefaultFractionDigits(),
                    policy.settlementRounding()).compareTo(amount.amount()) != 0) {
                throw new IllegalArgumentException("Settlement does not match calculation policy");
            }
        }

        /**
         * 返回最终金额减原始计算值的舍入差额，正数表示最终值高于原值。
         * @return 可正可负的高精度差额
         */
        public CalculatedAmount roundingDifference() {
            return new CalculatedAmount(amount.amount().subtract(calculated.value()), amount.currency());
        }
    }
}
