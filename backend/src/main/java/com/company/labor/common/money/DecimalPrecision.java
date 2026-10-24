package com.company.labor.common.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** 计价包内部的十进制边界校验，不替调用方舍入，也不定义收费或税务政策。 */
final class DecimalPrecision {
    /** 禁止实例化无状态的校验工具。 */
    private DecimalPrecision() {
    }

    /**
     * 去除无意义零位并检查技术容量，先限制指数再补整数零位，避免极端指数导致大量分配。
     * @param value 非空十进制值
     * @param integerDigits 最大整数位数
     * @param fractionalDigits 最大小数位数
     * @return 数值不变、无多余零位的十进制值
     * @throws NullPointerException 输入为空
     * @throws IllegalArgumentException 有效位数超出容量，不能静默舍入
     */
    static BigDecimal checked(BigDecimal value, int integerDigits, int fractionalDigits) {
        Objects.requireNonNull(value, "value");
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        long integers = (long) normalized.precision() - normalized.scale();
        if (integers > integerDigits || normalized.scale() > fractionalDigits) {
            throw new IllegalArgumentException("Decimal exceeds supported precision");
        }
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    /**
     * 校验调用方显式传入的分配比例，不提供默认值、档位或百分数自动转换。
     * @param ratio 以 1 表示全部的十进制比例，最多 12 位有效小数
     * @return 位于闭区间零到一的比例
     * @throws NullPointerException 比例为空
     * @throws IllegalArgumentException 比例越界或精度超限
     */
    static BigDecimal ratio(BigDecimal ratio) {
        BigDecimal checked = checked(ratio, 1, 12);
        if (checked.signum() < 0 || checked.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Ratio must be between zero and one");
        }
        return checked;
    }

    /**
     * 只接受具有明确最小货币单位的币种。
     * @param currency 非空币种
     * @return 原币种
     * @throws NullPointerException 币种为空
     * @throws IllegalArgumentException 币种未定义小数位
     */
    static Currency currency(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency must have a fixed minor unit");
        }
        return currency;
    }
}
