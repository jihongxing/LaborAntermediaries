package com.company.labor.common.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 将已确定的金额按显式输入进行守恒分配，不预置参与方、分成比例或平台尾差优先权。
 * 负金额按绝对值计算后镜像符号，仅提供数学能力，业务退款应关联原分配记录。
 */
public final class MoneyAllocation {
    /** 单次最多 1000 个接收标识，限制排序与载荷规模，不作为业务参与人数上限。 */
    private static final int MAX_RECIPIENTS = 1000;

    /** 禁止实例化无状态的分配工具。 */
    private MoneyAllocation() {
    }

    /**
     * 最大余数算法的内部最小单位分配信息，不承担收款对象真实性校验。
     * @param recipient 稳定接收标识
     * @param units 按绝对值向下取整的最小货币单位
     * @param remainder 尚未形成最小货币单位的小数余数
     */
    private record Part(String recipient, BigInteger units, BigDecimal remainder) {
    }

    /**
     * 按调用方当前传入的比例分配总额，余数大的优先补一最小单位，同余数按标识升序。
     * 比例必须精确合计为一；不自动归一化、不补默认档位，输入顺序不影响结果。
     * @param total 已形成最小货币单位的总额
     * @param ratios 非空比例集合，最多 1000 项，单项最多 12 位有效小数
     * @return 按补差顺序排列的不可变分配结果，合计严格等于总额
     * @throws NullPointerException 输入、接收标识、比例为空
     * @throws IllegalArgumentException 比例总和、精度、接收标识或数量不合法
     */
    public static Map<String, Money> largestRemainder(Money total, Map<String, BigDecimal> ratios) {
        Objects.requireNonNull(total, "total");
        Map<String, BigDecimal> inputs = recipients(ratios);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        int scale = total.currency().getDefaultFractionDigits();
        BigInteger totalUnits = total.amount().abs().movePointRight(scale).toBigIntegerExact();
        BigDecimal ratioSum = BigDecimal.ZERO;
        BigInteger assigned = BigInteger.ZERO;
        var parts = new ArrayList<Part>();
        for (var entry : inputs.entrySet()) {
            BigDecimal ratio = DecimalPrecision.ratio(entry.getValue());
            ratioSum = ratioSum.add(ratio);
            BigDecimal exact = new BigDecimal(totalUnits).multiply(ratio);
            BigInteger whole = exact.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
            assigned = assigned.add(whole);
            parts.add(new Part(entry.getKey(), whole, exact.subtract(new BigDecimal(whole))));
        }
        if (ratioSum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("Ratios must sum exactly to one");
        }
        int remaining = totalUnits.subtract(assigned).intValueExact();
        parts.sort(Comparator.comparing(Part::remainder).reversed().thenComparing(Part::recipient));
        Map<String, Money> result = new LinkedHashMap<>();
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            BigInteger units = index < remaining ? part.units().add(BigInteger.ONE) : part.units();
            BigDecimal value = new BigDecimal(units, scale);
            result.put(part.recipient(), new Money(total.amount().signum() < 0 ? value.negate() : value, total.currency()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 保留调用方已确定的各方金额，将剩余值分给显式指定的接收方，不默认给平台。
     * 每项必须同币种、与总额同向且不超分；允许负总额的数学镜像，不推断退款授权。
     * @param total 待分配总额
     * @param fixed 已确定的分配值，可为空集合但不可为 null
     * @param remainderRecipient 不得与 fixed 重复的余款接收标识
     * @return 包含余款方的不可变结果，合计严格等于总额
     * @throws NullPointerException 任一输入或分配值为空
     * @throws IllegalArgumentException 接收方重复、数量超限、币种或符号不同、分配超过总额
     */
    public static Map<String, Money> withRemainder(Money total, Map<String, Money> fixed, String remainderRecipient) {
        Objects.requireNonNull(total, "total");
        requireRecipient(remainderRecipient);
        Map<String, Money> result = recipients(fixed);
        if (result.containsKey(remainderRecipient) || result.size() >= MAX_RECIPIENTS) {
            throw new IllegalArgumentException("Duplicate or excessive remainder recipient");
        }
        BigDecimal assigned = BigDecimal.ZERO;
        for (Money amount : result.values()) {
            if (!total.currency().equals(amount.currency())
                    || (amount.amount().signum() != 0 && amount.amount().signum() != total.amount().signum())) {
                throw new IllegalArgumentException("Allocated money must match currency and direction");
            }
            assigned = assigned.add(amount.amount());
        }
        if (assigned.abs().compareTo(total.amount().abs()) > 0) {
            throw new IllegalArgumentException("Allocation exceeds total");
        }
        result.put(remainderRecipient, new Money(total.amount().subtract(assigned), total.currency()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 拷贝调用方输入，校验规模、标识与空值，避免输出受原 Map 后续修改影响。
     * @param source 接收标识与值的映射
     * @param <T> 非空输入值类型
     * @return 按接收标识排序的独立映射
     * @throws NullPointerException 映射或值为空
     * @throws IllegalArgumentException 映射超限或标识不合法
     */
    private static <T> Map<String, T> recipients(Map<String, T> source) {
        Objects.requireNonNull(source, "source");
        if (source.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("Too many allocation recipients");
        }
        Map<String, T> copy = new TreeMap<>();
        source.forEach((recipient, value) -> {
            requireRecipient(recipient);
            copy.put(recipient, Objects.requireNonNull(value, "allocation value"));
        });
        return copy;
    }

    /**
     * 限制稳定标识格式，标识不得填入姓名、银行账号等敏感数据。
     * @param recipient 接收标识
     * @throws NullPointerException 标识为空
     * @throws IllegalArgumentException 格式或长度不合法
     */
    private static void requireRecipient(String recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (!recipient.matches("[a-zA-Z0-9:._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid allocation recipient");
        }
    }
}
