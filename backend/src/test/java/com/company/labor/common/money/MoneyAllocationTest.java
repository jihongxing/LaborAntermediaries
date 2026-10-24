package com.company.labor.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证动态输入比例、守恒分配、稳定尾差和负数数学镜像；合成比例不是生产收费规则。 */
class MoneyAllocationTest {
    /** 最大余数分配按本次动态输入变化，各方结果合计等于总额。 */
    @Test
    void allocationUsesSuppliedRatiosWithoutPresets() {
        var first = MoneyAllocation.largestRemainder(Money.cny("55"),
                Map.of("party-a", new BigDecimal("0.173"), "party-b", new BigDecimal("0.827")));
        assertEquals(Money.cny("9.52"), first.get("party-a"));
        assertEquals(Money.cny("45.48"), first.get("party-b"));
        var changed = MoneyAllocation.largestRemainder(Money.cny("55"),
                Map.of("party-a", new BigDecimal("0.43"), "party-b", new BigDecimal("0.57")));
        assertEquals(Money.cny("23.65"), changed.get("party-a"));
        assertEquals(Money.cny("31.35"), changed.get("party-b"));
        assertEquals(new BigDecimal("55.00"), sum(first));
        assertThrows(UnsupportedOperationException.class, () -> first.put("other", Money.cny("0")));
    }

    /** 同余数按接收标识排序，与传入顺序、平台身份及运行次数无关。 */
    @Test
    void tiesAreDeterministicAndNotPlatformPreferred() {
        Map<String, BigDecimal> inputs = new LinkedHashMap<>();
        inputs.put("z-platform-fixture", new BigDecimal("0.5"));
        inputs.put("a-other-fixture", new BigDecimal("0.5"));
        var allocated = MoneyAllocation.largestRemainder(Money.cny("0.01"), inputs);
        assertEquals(Money.cny("0.01"), allocated.get("a-other-fixture"));
        assertEquals(Money.cny("0.00"), allocated.get("z-platform-fixture"));
        assertEquals(allocated, MoneyAllocation.largestRemainder(Money.cny("0.01"), new HashMap<>(inputs)));
    }

    /** 零总额、零比例和全部给一方均不会产生虚构尾差。 */
    @Test
    void zeroAndFullSharesRemainConservative() {
        var ratios = Map.of("empty", BigDecimal.ZERO, "full", BigDecimal.ONE);
        var allocated = MoneyAllocation.largestRemainder(Money.cny("0.01"), ratios);
        assertEquals(Money.cny("0"), allocated.get("empty"));
        assertEquals(Money.cny("0.01"), allocated.get("full"));
        assertEquals(new BigDecimal("0.00"), sum(MoneyAllocation.largestRemainder(Money.cny("0"), ratios)));
    }

    /** 比例精确合计必须为一，不为近似比例自动归一化，也不接受百分数数值。 */
    @Test
    void invalidRatioSetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MoneyAllocation.largestRemainder(Money.cny("1"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.largestRemainder(Money.cny("1"), Map.of("only", new BigDecimal("0.9"))));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.largestRemainder(Money.cny("1"), Map.of("only", new BigDecimal("25"))));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.largestRemainder(Money.cny("1"),
                        Map.of("negative", new BigDecimal("-0.1"), "other", new BigDecimal("1.1"))));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.largestRemainder(Money.cny("1"),
                        Map.of("tiny", new BigDecimal("0.0000000000001"), "other", new BigDecimal("0.9999999999999"))));
        assertThrows(NullPointerException.class, () -> MoneyAllocation.largestRemainder(null, Map.of("only", BigDecimal.ONE)));
        assertThrows(NullPointerException.class, () -> MoneyAllocation.largestRemainder(Money.cny("1"), null));
    }

    /** 不依赖 long 存储最小单位，支持 Money 容量内的大金额。 */
    @Test
    void largeAmountsDoNotOverflowLong() {
        Money total = Money.cny("123456789012345678901234567890.12");
        assertEquals(total, MoneyAllocation.largestRemainder(total, Map.of("only", BigDecimal.ONE)).get("only"));
    }

    /** 分配遵循币种自身最小单位，不把日元或三位小数币种当成人民币分。 */
    @Test
    void allocationRespectsCurrencyMinorUnit() {
        var ratios = Map.of("a", new BigDecimal("0.5"), "b", new BigDecimal("0.5"));
        Currency yen = Currency.getInstance("JPY");
        var yenShares = MoneyAllocation.largestRemainder(new Money(BigDecimal.ONE, yen), ratios);
        assertEquals(new Money(BigDecimal.ONE, yen), yenShares.get("a"));
        Currency dinar = Currency.getInstance("BHD");
        var dinarShares = MoneyAllocation.largestRemainder(new Money(new BigDecimal("0.001"), dinar), ratios);
        assertEquals(new Money(new BigDecimal("0.001"), dinar), dinarShares.get("a"));
        assertEquals(new Money(BigDecimal.ZERO, dinar), dinarShares.get("b"));
    }

    /** 多组总额与动态比例验证符号镜像和逐分守恒，退款仍须由业务引用原分配。 */
    @Test
    void varyingAmountsAndRatiosAlwaysConserveTotal() {
        for (int units = -50; units <= 50; units++) {
            Money total = new Money(BigDecimal.valueOf(units, 2), Currency.getInstance("CNY"));
            for (int index = 0; index <= 20; index++) {
                BigDecimal ratio = BigDecimal.valueOf(index).divide(new BigDecimal("20"));
                var ratios = Map.of("a", ratio, "b", BigDecimal.ONE.subtract(ratio));
                var allocated = MoneyAllocation.largestRemainder(total, ratios);
                assertEquals(total.amount(), sum(allocated));
                var mirrored = MoneyAllocation.largestRemainder(
                        new Money(total.amount().negate(), total.currency()), ratios);
                for (String recipient : allocated.keySet()) {
                    assertEquals(allocated.get(recipient).amount().negate(), mirrored.get(recipient).amount());
                }
            }
        }
    }

    /** 输入只在本次调用有效，结果不随调用方后续修改比例集合而改变。 */
    @Test
    void inputChangesCannotMutatePriorAllocation() {
        var ratios = new HashMap<>(Map.of("a", new BigDecimal("0.3"), "b", new BigDecimal("0.7")));
        var allocated = MoneyAllocation.largestRemainder(Money.cny("1"), ratios);
        ratios.put("a", BigDecimal.ONE);
        assertEquals(Money.cny("0.30"), allocated.get("a"));
    }

    /** 余款方必须显式指定，结果与固定分配输入隔离，且总额严格守恒。 */
    @Test
    void remainderGoesOnlyToExplicitRecipient() {
        var fixed = new HashMap<>(Map.of("supplier-fixture", Money.cny("0.19")));
        var allocated = MoneyAllocation.withRemainder(Money.cny("0.55"), fixed, "designated-fixture");
        assertEquals(Money.cny("0.36"), allocated.get("designated-fixture"));
        assertEquals(new BigDecimal("0.55"), sum(allocated));
        fixed.clear();
        assertEquals(Money.cny("0.19"), allocated.get("supplier-fixture"));
        assertEquals(Money.cny("1"), MoneyAllocation.withRemainder(Money.cny("1"), Map.of(), "only").get("only"));
        assertThrows(UnsupportedOperationException.class, () -> allocated.clear());
    }

    /** 指定余款支持负数数学镜像和零额，不产生相反方向的隐式抵扣。 */
    @Test
    void explicitRemainderSupportsMatchingDirectionOnly() {
        var allocated = MoneyAllocation.withRemainder(Money.cny("-0.55"), Map.of("a", Money.cny("-0.19")), "b");
        assertEquals(Money.cny("-0.36"), allocated.get("b"));
        assertEquals(new BigDecimal("-0.55"), sum(allocated));
        assertEquals(Money.cny("0"), MoneyAllocation.withRemainder(Money.cny("0"), Map.of("a", Money.cny("0")), "b").get("b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("1"), Map.of("a", Money.cny("-0.1")), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("-1"), Map.of("a", Money.cny("0.1")), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("0"), Map.of("a", Money.cny("0.1")), "b"));
    }

    /** 超分、币种不同、重复余款方及缺失输入一律拒绝。 */
    @Test
    void inconsistentRemainderInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("1"), Map.of("a", Money.cny("1.01")), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("-1"), Map.of("a", Money.cny("-1.01")), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("1"),
                        Map.of("a", new Money(BigDecimal.ZERO, Currency.getInstance("USD"))), "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.withRemainder(Money.cny("1"), Map.of("a", Money.cny("1")), "a"));
        assertThrows(NullPointerException.class, () -> MoneyAllocation.withRemainder(null, Map.of(), "a"));
        assertThrows(NullPointerException.class, () -> MoneyAllocation.withRemainder(Money.cny("1"), null, "a"));
        assertThrows(NullPointerException.class, () -> MoneyAllocation.withRemainder(Money.cny("1"), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> MoneyAllocation.withRemainder(Money.cny("1"), Map.of(), "bad key"));
    }

    /** 非法标识、空值和过大映射均不进入排序或分配，边界数量可以正常执行。 */
    @Test
    void recipientsAreValidatedAndBounded() {
        Map<String, BigDecimal> nullKey = new HashMap<>();
        nullKey.put(null, BigDecimal.ONE);
        assertThrows(NullPointerException.class, () -> MoneyAllocation.largestRemainder(Money.cny("1"), nullKey));
        Map<String, BigDecimal> nullValue = new HashMap<>();
        nullValue.put("a", null);
        assertThrows(NullPointerException.class, () -> MoneyAllocation.largestRemainder(Money.cny("1"), nullValue));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyAllocation.largestRemainder(Money.cny("1"), Map.of("../bad", BigDecimal.ONE)));
        Map<String, Money> fixed = new HashMap<>();
        Map<String, BigDecimal> ratios = new HashMap<>();
        for (int index = 0; index < 1000; index++) {
            fixed.put("recipient-" + index, Money.cny("0"));
            ratios.put("recipient-" + index, new BigDecimal("0.001"));
        }
        assertEquals(new BigDecimal("1.00"), sum(MoneyAllocation.largestRemainder(Money.cny("1"), ratios)));
        assertThrows(IllegalArgumentException.class, () -> MoneyAllocation.withRemainder(Money.cny("1"), fixed, "extra"));
        ratios.put("extra", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> MoneyAllocation.largestRemainder(Money.cny("1"), ratios));
    }

    /**
     * 同币种结果按原小数位汇总，避免测试通过再次舍入掩盖问题。
     * @param allocated 分配结果
     * @return 精确汇总值
     */
    private BigDecimal sum(Map<String, Money> allocated) {
        return allocated.values().stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
