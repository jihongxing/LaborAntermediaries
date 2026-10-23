package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MoneyUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("金额工具类测试")
class MoneyUtilTest {

    @Test
    @DisplayName("加法测试")
    void testAdd() {
        BigDecimal result = MoneyUtil.add("100.50", "200.30");
        assertEquals(new BigDecimal("300.80"), result);

        // null值处理
        result = MoneyUtil.add(null, new BigDecimal("100"));
        assertEquals(new BigDecimal("100"), result);
    }

    @Test
    @DisplayName("减法测试")
    void testSubtract() {
        BigDecimal result = MoneyUtil.subtract("500.50", "200.30");
        assertEquals(new BigDecimal("300.20"), result);
    }

    @Test
    @DisplayName("乘法测试")
    void testMultiply() {
        BigDecimal result = MoneyUtil.multiply("10.5", "2");
        assertEquals(new BigDecimal("21.00"), result);
    }

    @Test
    @DisplayName("除法测试")
    void testDivide() {
        BigDecimal result = MoneyUtil.divide("100", "3");
        assertEquals(new BigDecimal("33.33"), result);

        // 除数为0
        assertThrows(ArithmeticException.class, () -> {
            MoneyUtil.divide("100", "0");
        });
    }

    @Test
    @DisplayName("格式化测试")
    void testFormat() {
        String formatted = MoneyUtil.format(new BigDecimal("1234.56"));
        assertEquals("¥1,234.56", formatted);

        formatted = MoneyUtil.formatWithoutSymbol(new BigDecimal("1234.56"));
        assertEquals("1,234.56", formatted);
    }

    @Test
    @DisplayName("元转分测试")
    void testYuanToFen() {
        Long fen = MoneyUtil.yuanToFen(new BigDecimal("12.34"));
        assertEquals(1234L, fen);
    }

    @Test
    @DisplayName("分转元测试")
    void testFenToYuan() {
        BigDecimal yuan = MoneyUtil.fenToYuan(1234L);
        assertEquals(new BigDecimal("12.34"), yuan);
    }

    @Test
    @DisplayName("比较测试")
    void testCompare() {
        assertTrue(MoneyUtil.isPositive(new BigDecimal("100")));
        assertTrue(MoneyUtil.isNegative(new BigDecimal("-100")));
        assertTrue(MoneyUtil.isZero(BigDecimal.ZERO));

        assertEquals(1, MoneyUtil.compare(new BigDecimal("100"), new BigDecimal("50")));
        assertEquals(0, MoneyUtil.compare(new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(-1, MoneyUtil.compare(new BigDecimal("50"), new BigDecimal("100")));
    }

    @Test
    @DisplayName("最大值最小值测试")
    void testMaxMin() {
        BigDecimal max = MoneyUtil.max(new BigDecimal("100"), new BigDecimal("200"));
        assertEquals(new BigDecimal("200"), max);

        BigDecimal min = MoneyUtil.min(new BigDecimal("100"), new BigDecimal("200"));
        assertEquals(new BigDecimal("100"), min);
    }
}
