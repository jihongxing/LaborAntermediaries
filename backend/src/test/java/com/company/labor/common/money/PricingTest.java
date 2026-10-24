package com.company.labor.common.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

/** 验证高精度计价、中间计算和最终结算分离，所有比例均为合成测试输入而非生产档位。 */
class PricingTest {
    /** 合成测试使用的人民币币种。 */
    private static final Currency CNY = Currency.getInstance("CNY");
    /** 合成策略，没有在生产代码中登记或设为默认。 */
    private static final CalculationPolicy POLICY =
            new CalculationPolicy("fixture.calculation", 1, 24, RoundingMode.HALF_EVEN, RoundingMode.HALF_UP);

    /** 单价允许万分位并保留精度，多余零位不改变数值。 */
    @Test
    void unitPriceRetainsSubCentPrecision() {
        var price = new UnitPrice(new BigDecimal("0.36250000"), CNY, "fixture.person_day");
        assertEquals(new BigDecimal("0.3625"), price.value());
        assertEquals(new CalculatedAmount(new BigDecimal("36.25"), CNY), price.calculate(new BigDecimal("100")));
        assertEquals(new BigDecimal("0.18125"), price.calculate(new BigDecimal("0.5")).value());
        assertEquals(new BigDecimal("0"), price.calculate(BigDecimal.ZERO).value());
        assertThrows(ArithmeticException.class, () -> Money.cny(price.value().toPlainString()));
    }

    /** 不为单价设置默认单位，拒绝负单价、错误币种、超精度及非法单位。 */
    @Test
    void unitPriceRejectsInvalidContract() {
        assertThrows(IllegalArgumentException.class, () -> price("-0.1"));
        assertThrows(IllegalArgumentException.class, () -> price("0.000000001"));
        assertThrows(IllegalArgumentException.class, () -> price("1E+30"));
        assertThrows(IllegalArgumentException.class, () -> price("1E+2147483647"));
        assertThrows(IllegalArgumentException.class, () -> new UnitPrice(BigDecimal.ONE, CNY, ""));
        assertThrows(IllegalArgumentException.class, () -> new UnitPrice(BigDecimal.ONE, CNY, "../day"));
        assertThrows(IllegalArgumentException.class, () -> new UnitPrice(BigDecimal.ONE, CNY, null));
        assertThrows(IllegalArgumentException.class,
                () -> new UnitPrice(BigDecimal.ONE, Currency.getInstance("XXX"), "fixture.day"));
        assertThrows(NullPointerException.class, () -> new UnitPrice(null, CNY, "fixture.day"));
        assertThrows(NullPointerException.class, () -> new UnitPrice(BigDecimal.ONE, null, "fixture.day"));
    }

    /** 数量精度、符号和结果容量均受约束，但数量有效性和单位一致性仍由业务负责。 */
    @Test
    void quantityMustBeExplicitAndBounded() {
        var price = price("1");
        assertThrows(NullPointerException.class, () -> price.calculate(null));
        assertThrows(IllegalArgumentException.class, () -> price.calculate(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> price.calculate(new BigDecimal("0.000000001")));
        assertThrows(IllegalArgumentException.class, () -> price.calculate(new BigDecimal("1E+18")));
        assertThrows(IllegalArgumentException.class, () -> price("1E+29").calculate(new BigDecimal("1E+17")));
        assertEquals(new BigDecimal("0.0000000000000001"),
                price("0.00000001").calculate(new BigDecimal("0.00000001")).value());
    }

    /** 极端零指数不会触发补零内存分配，正常的大指数在补零前拒绝。 */
    @Test
    void technicalBoundsRejectWithoutImplicitRounding() {
        assertEquals(BigDecimal.ZERO, amount("0E+2147483647").value());
        assertEquals(new BigDecimal("100000"), amount("1E+5").value());
        assertThrows(IllegalArgumentException.class, () -> amount("1E+44"));
        assertThrows(IllegalArgumentException.class, () -> amount("1E-33"));
        assertThrows(IllegalArgumentException.class, () -> amount("1E+2147483647"));
        assertThrows(NullPointerException.class, () -> new CalculatedAmount(null, CNY));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculatedAmount(BigDecimal.ONE, Currency.getInstance("XXX")));
    }

    /** 份额完全随调用方输入变化，不存在生产预置比例或百分数自动推断。 */
    @Test
    void portionsUseOnlyExplicitRuntimeRatios() {
        var base = amount("0.25");
        assertEquals(amount("0.04325"), base.portion(new BigDecimal("0.173")));
        assertEquals(amount("0.20675"), base.portion(new BigDecimal("0.827")));
        assertEquals(amount("0.1425"), base.portion(new BigDecimal("0.57")));
        assertEquals(base, base.portion(BigDecimal.ONE));
        assertEquals(amount("0"), base.portion(BigDecimal.ZERO));
        assertEquals(amount("-0.04325"), amount("-0.25").portion(new BigDecimal("0.173")));
        assertThrows(NullPointerException.class, () -> base.portion(null));
        assertThrows(IllegalArgumentException.class, () -> base.portion(new BigDecimal("25")));
        assertThrows(IllegalArgumentException.class, () -> base.portion(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> base.portion(new BigDecimal("0.1234567890123")));
        assertEquals(amount("0.25"), base.portion(new BigDecimal("1.0000000000000")));
    }

    /** 累加、相减和系数相乘不进行两位舍入，也不允许跨币种运算。 */
    @Test
    void arithmeticPreservesIntermediateDigits() {
        assertEquals(amount("0.005"), amount("0.0025").add(amount("0.0025")));
        assertEquals(amount("-0.001"), amount("0.0025").subtract(amount("0.0035")));
        assertEquals(amount("0.010875"), amount("0.3625").multiply(new BigDecimal("0.03")));
        assertEquals(amount("-0.725"), amount("0.3625").multiply(new BigDecimal("-2")));
        var otherCurrency = new CalculatedAmount(BigDecimal.ONE, Currency.getInstance("USD"));
        assertThrows(IllegalArgumentException.class, () -> amount("1").add(otherCurrency));
        assertThrows(IllegalArgumentException.class, () -> amount("1").subtract(otherCurrency));
        assertThrows(NullPointerException.class, () -> amount("1").add(null));
        assertThrows(NullPointerException.class, () -> amount("1").multiply(null));
        assertThrows(IllegalArgumentException.class, () -> amount("1E-32").multiply(new BigDecimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> amount("1E+43").multiply(BigDecimal.TEN));
    }

    /** 除法必须传策略，循环小数按显式精度处理，禁止模式下应拒绝而非猜测。 */
    @Test
    void divisionRequiresExplicitPrecisionAndRounding() {
        var down = new CalculationPolicy("fixture.division", 2, 8, RoundingMode.DOWN, RoundingMode.UNNECESSARY);
        assertEquals(amount("0.33333333"), amount("1").divide(new BigDecimal("3"), down));
        assertEquals(amount("0.333333333333333333333333"), amount("1").divide(new BigDecimal("3"), POLICY));
        assertEquals(amount("0.5"), amount("1").divide(new BigDecimal("2"), down));
        var exact = new CalculationPolicy("fixture.exact", 1, 8, RoundingMode.UNNECESSARY, RoundingMode.UNNECESSARY);
        assertThrows(ArithmeticException.class, () -> amount("1").divide(new BigDecimal("3"), exact));
        assertThrows(ArithmeticException.class, () -> amount("1").divide(BigDecimal.ZERO, POLICY));
        assertThrows(NullPointerException.class, () -> amount("1").divide(BigDecimal.TEN, null));
        assertThrows(NullPointerException.class, () -> amount("1").divide(null, POLICY));
        assertThrows(IllegalArgumentException.class, () -> amount("1").divide(new BigDecimal("1E-33"), POLICY));
    }

    /** 策略缺少标识、版本、模式或精度越界时拒绝，不产生默认业务规则。 */
    @Test
    void policyRejectsMissingOrInvalidChoices() {
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy(null, 1, 8, RoundingMode.DOWN, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy("not a key", 1, 8, RoundingMode.DOWN, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy("fixture", 0, 8, RoundingMode.DOWN, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy("fixture", 1, -1, RoundingMode.DOWN, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy("fixture", 1, 33, RoundingMode.DOWN, RoundingMode.HALF_UP));
        assertThrows(NullPointerException.class,
                () -> new CalculationPolicy("fixture", 1, 8, null, RoundingMode.HALF_UP));
        assertThrows(NullPointerException.class,
                () -> new CalculationPolicy("fixture", 1, 8, RoundingMode.DOWN, null));
    }

    /** 先按业务范围汇总与逐单位舍入结果不同，底座不替业务选择范围。 */
    @Test
    void settlementKeepsAggregationBoundaryExplicit() {
        var price = price("0.3625");
        var aggregated = POLICY.settle(price.calculate(new BigDecimal("100")));
        assertEquals(Money.cny("36.25"), aggregated.amount());
        assertEquals(amount("0"), aggregated.roundingDifference());
        var perUnit = POLICY.settle(price.calculate(BigDecimal.ONE));
        assertEquals(Money.cny("0.36"), perUnit.amount());
        assertEquals(amount("-0.0025"), perUnit.roundingDifference());
        assertEquals(new BigDecimal("0.25"),
                aggregated.amount().amount().subtract(perUnit.amount().amount().multiply(new BigDecimal("100"))));
    }

    /** 半分、负数和不同币种的小数位均按显式模式处理，保留策略版本用于追溯。 */
    @Test
    void settlementUsesCurrencyScaleAndChosenMode() {
        var halfEven = new CalculationPolicy("fixture.even", 3, 12, RoundingMode.HALF_EVEN, RoundingMode.HALF_EVEN);
        assertEquals(Money.cny("0.00"), halfEven.settle(amount("0.005")).amount());
        assertEquals(Money.cny("0.01"), POLICY.settle(amount("0.005")).amount());
        assertEquals(Money.cny("-0.01"), POLICY.settle(amount("-0.005")).amount());
        assertEquals(amount("-0.005"), POLICY.settle(amount("-0.005")).roundingDifference());
        var yen = new CalculatedAmount(new BigDecimal("12.5"), Currency.getInstance("JPY"));
        assertEquals(new BigDecimal("13"), POLICY.settle(yen).amount().amount());
        var dinar = new CalculatedAmount(new BigDecimal("0.1235"), Currency.getInstance("BHD"));
        assertEquals(new BigDecimal("0.124"), POLICY.settle(dinar).amount().amount());
        assertEquals(3, halfEven.settle(amount("1")).policy().version());
        assertThrows(NullPointerException.class, () -> POLICY.settle(null));
        assertThrows(IllegalArgumentException.class, () -> POLICY.settle(amount("1E+40")));
        var exact = new CalculationPolicy("fixture.exact", 1, 16, RoundingMode.UNNECESSARY, RoundingMode.UNNECESSARY);
        assertThrows(ArithmeticException.class, () -> exact.settle(amount("0.001")));
    }

    /** 封套不能把另一币种、另一最终值或不完整策略伪装成当前计算结果。 */
    @Test
    void settlementEnvelopeValidatesInternalConsistency() {
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy.Settlement(amount("1"), Money.cny("2"), POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculationPolicy.Settlement(amount("1"),
                        new Money(BigDecimal.ONE, Currency.getInstance("USD")), POLICY));
        assertThrows(NullPointerException.class,
                () -> new CalculationPolicy.Settlement(null, Money.cny("1"), POLICY));
        assertThrows(NullPointerException.class,
                () -> new CalculationPolicy.Settlement(amount("1"), null, POLICY));
        assertThrows(NullPointerException.class,
                () -> new CalculationPolicy.Settlement(amount("1"), Money.cny("1"), null));
    }

    /**
     * 高精度单价、计算值和封套按字符串输出，并能在同一契约下反序列化复算。
     * @throws Exception JSON 编解码失败
     */
    @Test
    void jsonRoundTripPreservesHighPrecisionAndPolicy() throws Exception {
        var mapper = new ObjectMapper();
        var price = price("0.00000001");
        String priceJson = mapper.writeValueAsString(price);
        assertEquals("0.00000001", mapper.readTree(priceJson).get("value").textValue());
        assertEquals(price, mapper.readValue(priceJson, UnitPrice.class));
        var tiny = amount("0.00000000000000000000000000000001");
        String calculatedJson = mapper.writeValueAsString(tiny);
        assertEquals(tiny.value().toPlainString(), mapper.readTree(calculatedJson).get("value").textValue());
        assertEquals(tiny, mapper.readValue(calculatedJson, CalculatedAmount.class));
        var settlement = POLICY.settle(amount("0.3625"));
        String json = mapper.writeValueAsString(settlement);
        assertEquals("0.36", mapper.readTree(json).get("amount").get("amount").textValue());
        assertEquals(settlement, mapper.readValue(json, CalculationPolicy.Settlement.class));
        assertThrows(Exception.class,
                () -> mapper.readValue("{\"value\":\"0.000000001\",\"currency\":\"CNY\",\"unit\":\"fixture.day\"}", UnitPrice.class));
    }

    /**
     * 创建测试单价，单位不代表生产配置。
     * @param value 十进制单价文本
     * @return 合成单价
     */
    private UnitPrice price(String value) {
        return new UnitPrice(new BigDecimal(value), CNY, "fixture.person_day");
    }

    /**
     * 创建测试计算值，不经过浮点数。
     * @param value 十进制计算值文本
     * @return 人民币计算值
     */
    private CalculatedAmount amount(String value) {
        return new CalculatedAmount(new BigDecimal(value), CNY);
    }
}
