package com.company.labor.common.money;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 带币种的不可变已确定金额，不用于存储尚待计价的费率或工时。
 * 不隐式舍入、不允许跨币种相加；负数可表达调整，不代表付款已获授权。
 * @param amount 按币种最小单位精确表达的金额
 * @param currency ISO 4217 币种，不支持没有明确小数位的币种
 */
public record Money(@JsonSerialize(using = ToStringSerializer.class) BigDecimal amount, Currency currency) {
    /** 技术精度上限为 38 位，防止极端指数输入在补齐小数位时造成过量内存分配。 */
    private static final int MAX_PRECISION = 38;
    /**
     * 校验并统一币种精度，允许去除多余零位，但不允许丢失非零小数。
     * @param amount 非空金额
     * @param currency 非空币种
     * @throws ArithmeticException 金额无法用币种精度精确表示
     * @throws IllegalArgumentException 币种没有固定最小单位或金额超出技术精度上限
     * @throws NullPointerException 金额或币种为空
     */
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) {
            throw new IllegalArgumentException("Currency must have a fixed minor unit");
        }
        if ((long) amount.precision() - amount.scale() > MAX_PRECISION - scale) {
            throw new IllegalArgumentException("Amount exceeds supported precision");
        }
        amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    /**
     * 从十进制文本构造人民币金额，不经过浮点数。
     * @param amount 十进制金额
     * @return 人民币金额
     * @throws NumberFormatException 文本不是合法十进制数
     * @throws ArithmeticException 金额存在分以下的非零小数
     */
    public static Money cny(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CNY"));
    }

    /**
     * 精确转换为最小货币单位，支付适配前还须执行业务授权。
     * @return 最小单位金额，人民币对应分
     * @throws ArithmeticException 结果超出 long 范围
     */
    public long minorUnitsExact() {
        return amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
    }

    /**
     * 同币种金额相加，不修改输入。
     * @param other 同币种金额
     * @return 相加后的金额
     * @throws IllegalArgumentException 币种不同
     */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * 同币种金额相减，允许负结果用于差额表达。
     * @param other 同币种金额
     * @return 相减后的金额
     * @throws IllegalArgumentException 币种不同
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * 检查币种一致，禁止把汇率或换汇规则隐藏在金额工具中。
     * @param other 待运算金额
     * @throws IllegalArgumentException 币种不同
     * @throws NullPointerException 待运算金额为空
     */
    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency conversion requires an explicit business policy");
        }
    }
}
