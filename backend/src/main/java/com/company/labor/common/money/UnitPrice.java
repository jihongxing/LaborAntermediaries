package com.company.labor.common.money;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * 带币种和计价单位的非负单价，不是已经舍入到分的应收应付。
 * 计价单位由业务登记，不预置收费、分成比例或人天数量的业务含义。
 * @param value 单价，最多 30 位整数、8 位有效小数
 * @param currency 币种
 * @param unit 业务登记的稳定计价单位标识，不使用展示文案或敏感数据
 */
public record UnitPrice(@JsonSerialize(using = DecimalTextSerializer.class) BigDecimal value,
                        Currency currency, String unit) {
    /**
     * 校验单价及计价单位，超精度时拒绝而非截断。
     * @param value 非负单价
     * @param currency 具有最小货币单位的币种
     * @param unit 最多 64 位小写英文单位标识
     * @throws NullPointerException 数值或币种为空
     * @throws IllegalArgumentException 单价、单位或币种不满足技术约束
     */
    public UnitPrice {
        value = DecimalPrecision.checked(value, 30, 8);
        currency = DecimalPrecision.currency(currency);
        if (value.signum() < 0 || unit == null || !unit.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid unit price or pricing unit");
        }
    }

    /**
     * 精确计算数量对应的未结算金额，不对每个计价单位先舍入。
     * 调用方负责核实数量与单价单位一致、来源有效以及履约已确认。
     * @param quantity 非负数量，最多 18 位整数、8 位有效小数
     * @return 未按最小货币单位舍入的计算金额
     * @throws NullPointerException 数量为空
     * @throws IllegalArgumentException 数量为负或输入、结果精度超限
     */
    public CalculatedAmount calculate(BigDecimal quantity) {
        BigDecimal checked = DecimalPrecision.checked(quantity, 18, 8);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException("Quantity must not be negative");
        }
        return new CalculatedAmount(value.multiply(checked), currency);
    }
}
