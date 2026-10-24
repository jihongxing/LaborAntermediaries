package com.company.labor.common.money;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 未形成最终结算金额的高精度计算值，允许负数表达数学调整，不代表付款或退款授权。
 * 加减乘不隐式舍入；有限容量是技术边界，不代表无限精度，也不决定业务汇总范围。
 * @param value 最多 44 位整数、32 位有效小数的计算值
 * @param currency 币种
 */
public record CalculatedAmount(@JsonSerialize(using = DecimalTextSerializer.class) BigDecimal value, Currency currency) {
    /**
     * 校验高精度计算容量，超过容量直接拒绝，不隐式降精度。
     * @param value 非空计算值
     * @param currency 具有最小货币单位的币种
     * @throws NullPointerException 数值或币种为空
     * @throws IllegalArgumentException 精度或币种不合法
     */
    public CalculatedAmount {
        value = DecimalPrecision.checked(value, 44, 32);
        currency = DecimalPrecision.currency(currency);
    }

    /**
     * 同币种精确累加，不在账单行或个人层面自动舍入。
     * @param other 同币种计算值
     * @return 累加结果
     * @throws IllegalArgumentException 币种不同或结果超出技术容量
     */
    public CalculatedAmount add(CalculatedAmount other) {
        requireSameCurrency(other);
        return new CalculatedAmount(value.add(other.value), currency);
    }

    /**
     * 同币种精确相减，负结果仅作为计算值。
     * @param other 同币种计算值
     * @return 差额
     * @throws IllegalArgumentException 币种不同或结果超出技术容量
     */
    public CalculatedAmount subtract(CalculatedAmount other) {
        requireSameCurrency(other);
        return new CalculatedAmount(value.subtract(other.value), currency);
    }

    /**
     * 按显式无量纲因子精确相乘，可用于业务已确定的系数，不提供税率或加价默认值。
     * @param factor 最多 44 位整数、32 位有效小数的因子
     * @return 未舍入的乘积
     * @throws IllegalArgumentException 因子或结果超出技术容量
     */
    public CalculatedAmount multiply(BigDecimal factor) {
        BigDecimal checked = DecimalPrecision.checked(factor, 44, 32);
        return new CalculatedAmount(value.multiply(checked), currency);
    }

    /**
     * 计算调用方显式指定的份额，不预置任何业务分成档位。
     * @param ratio 零到一的比例，最多 12 位有效小数；不能直接传百分数数值
     * @return 未舍入的份额
     * @throws IllegalArgumentException 比例或计算结果超出容量
     */
    public CalculatedAmount portion(BigDecimal ratio) {
        return new CalculatedAmount(value.multiply(DecimalPrecision.ratio(ratio)), currency);
    }

    /**
     * 使用显式策略进行除法，循环小数不会落入默认两位舍入。
     * @param divisor 非零无量纲除数，最多 44 位整数、32 位有效小数
     * @param policy 显式提供并由调用方保存版本的计算策略
     * @return 按策略处理精度的商
     * @throws ArithmeticException 除数为零或策略禁止必要的舍入
     * @throws IllegalArgumentException 输入或结果超出技术容量
     * @throws NullPointerException 策略或除数为空
     */
    public CalculatedAmount divide(BigDecimal divisor, CalculationPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        BigDecimal checked = DecimalPrecision.checked(divisor, 44, 32);
        return new CalculatedAmount(value.divide(checked, policy.divisionScale(), policy.divisionRounding()), currency);
    }

    /**
     * 禁止跨币种相加减，也不在技术包中暗含换汇。
     * @param other 待运算计算值
     * @throws NullPointerException 输入为空
     * @throws IllegalArgumentException 币种不同
     */
    private void requireSameCurrency(CalculatedAmount other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currencies must match");
        }
    }
}
