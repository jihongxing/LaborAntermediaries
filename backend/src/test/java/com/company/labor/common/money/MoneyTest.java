package com.company.labor.common.money;

import com.company.labor.common.util.MoneyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 验证交易金额的币种、最小单位、精度与并发格式化边界。 */
class MoneyTest {
    /** 数值相等的不同小数位输入统一为相同金额。 */
    @Test
    void normalizesWithoutRounding() {
        assertEquals(Money.cny("12.34"), Money.cny("12.3400"));
        assertEquals(1234L, Money.cny("12.34").minorUnitsExact());
        assertEquals(new BigDecimal("12.00"), Money.cny("12").amount());
        assertEquals(100, new Money(new BigDecimal("100"), Currency.getInstance("JPY")).minorUnitsExact());
    }

    /** 禁止分以下精度被隐式舍入或截断。 */
    @Test
    void rejectsLossOfPrecisionAndUnknownAmounts() {
        assertThrows(ArithmeticException.class, () -> Money.cny("0.001"));
        assertThrows(NullPointerException.class, () -> new Money(null, Currency.getInstance("CNY")));
        assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.ONE, Currency.getInstance("XXX")));
        assertThrows(ArithmeticException.class, () -> MoneyUtil.yuanToFen(new BigDecimal("0.001")));
    }

    /** 最小单位转换超出长整数范围时拒绝，不发生溢出后继续付款的情况。 */
    @Test
    void minorUnitsRejectOverflow() {
        assertThrows(ArithmeticException.class, () -> Money.cny("92233720368547758.08").minorUnitsExact());
        assertThrows(ArithmeticException.class, () -> MoneyUtil.yuanToFen(new BigDecimal("92233720368547758.08")));
        assertEquals(Long.MAX_VALUE, Money.cny("92233720368547758.07").minorUnitsExact());
    }

    /**
     * JSON 以十进制文本传递金额，且极端正指数在补位前被技术精度限制拒绝。
     * @throws Exception JSON 编码或读取失败
     */
    @Test
    void jsonPreservesExactDecimalAndBoundsExtremeExponent() throws Exception {
        var mapper = new ObjectMapper();
        var encoded = mapper.readTree(mapper.writeValueAsString(Money.cny("12345678901234567890.12")));
        assertTrue(encoded.get("amount").isTextual());
        assertEquals("12345678901234567890.12", encoded.get("amount").textValue());
        assertEquals("CNY", encoded.get("currency").textValue());
        assertThrows(IllegalArgumentException.class, () -> Money.cny("1E+2147483647"));
    }

    /** 不同币种禁止相加，负值仅表示金额差额而非付款授权。 */
    @Test
    void arithmeticRequiresSameCurrency() {
        assertEquals(Money.cny("3.50"), Money.cny("1.25").add(Money.cny("2.25")));
        assertEquals(Money.cny("-1.00"), Money.cny("1").subtract(Money.cny("2")));
        assertThrows(IllegalArgumentException.class,
                () -> Money.cny("1").add(new Money(BigDecimal.ONE, Currency.getInstance("USD"))));
        assertThrows(IllegalArgumentException.class,
                () -> Money.cny("1").subtract(new Money(BigDecimal.ONE, Currency.getInstance("USD"))));
    }

    /** 兼容格式化工具多线程调用时不得共享有状态的 DecimalFormat。 */
    @Test
    void formattingDoesNotShareMutableState() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 200; index++) {
                futures.add(executor.submit(() -> MoneyUtil.format(new BigDecimal("1234567.89"))));
            }
            for (var future : futures) {
                assertEquals("¥1,234,567.89", future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
