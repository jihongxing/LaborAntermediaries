package com.company.labor.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 金额工具类
 *
 * <p>所有金额计算必须使用BigDecimal，禁止使用double/float。
 * 本工具类提供了常用的金额计算和格式化方法。
 * BigDecimal 加减和比较将 null 视为零，乘除默认保留两位并四舍五入；
 * 字符串重载直接解析数值，不接受 null 或非法数值。新交易金额推荐使用 common.money.Money 显式携带币种。
 *
 * <p>使用示例：
 * <pre>
 * // 加法
 * BigDecimal result = MoneyUtil.add("100.50", "200.30"); // 300.80
 *
 * // 格式化
 * String formatted = MoneyUtil.format(new BigDecimal("1234.56")); // ¥1,234.56
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class MoneyUtil {

    /** 禁止实例化金额工具类。 */
    private MoneyUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * 默认精度（小数点后2位）
     */
    private static final int DEFAULT_SCALE = 2;

    /**
     * 默认舍入模式（四舍五入）
     */
    private static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * 带千分位的显示格式，每次格式化创建独立实例，避免 DecimalFormat 跨线程共享。
     */
    private static final String MONEY_PATTERN = "#,##0.00";

    /**
     * 加法
     *
     * @param a 加数
     * @param b 加数
     * @return 和
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.add(b);
    }

    /**
     * 加法（字符串参数）
     *
     * @param a 加数
     * @param b 加数
     * @return 和
     */
    public static BigDecimal add(String a, String b) {
        return add(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 减法
     *
     * @param a 被减数
     * @param b 减数
     * @return 差
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.subtract(b);
    }

    /**
     * 减法（字符串参数）
     *
     * @param a 被减数
     * @param b 减数
     * @return 差
     */
    public static BigDecimal subtract(String a, String b) {
        return subtract(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 乘法
     *
     * @param a 乘数
     * @param b 乘数
     * @return 积
     * @deprecated 兼容旧代码的两位舍入；新增计价使用 UnitPrice 或 CalculatedAmount，禁止中间值提前舍入
     */
    @Deprecated
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.multiply(b).setScale(DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    /**
     * 乘法（字符串参数）
     *
     * @param a 乘数
     * @param b 乘数
     * @return 积
     * @deprecated 兼容旧代码的两位舍入；新增计价使用 UnitPrice 或 CalculatedAmount
     */
    @Deprecated
    public static BigDecimal multiply(String a, String b) {
        return multiply(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 除法
     *
     * @param a 被除数
     * @param b 除数
     * @return 商
     * @throws ArithmeticException 除数为0
     * @deprecated 兼容旧代码的两位舍入；新增计价使用 CalculatedAmount.divide 并显式传入策略
     */
    @Deprecated
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("除数不能为0");
        }
        return a.divide(b, DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    /**
     * 除法（字符串参数）
     *
     * @param a 被除数
     * @param b 除数
     * @return 商
     * @throws NumberFormatException 参数不是合法十进制数值
     * @throws ArithmeticException 除数为零
     * @deprecated 兼容旧代码的两位舍入；新增计价使用 CalculatedAmount.divide 并显式传入策略
     */
    @Deprecated
    public static BigDecimal divide(String a, String b) {
        return divide(new BigDecimal(a), new BigDecimal(b));
    }

    /**
     * 格式化金额（带千分位和货币符号）
     *
     * <p>示例：1234.56 -> ¥1,234.56
     *
     * @param amount 金额
     * @return 格式化后的字符串
     */
    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "¥0.00";
        }
        return "¥" + new DecimalFormat(MONEY_PATTERN).format(amount);
    }

    /**
     * 格式化金额（不带货币符号）
     *
     * <p>示例：1234.56 -> 1,234.56
     *
     * @param amount 金额
     * @return 格式化后的字符串
     */
    public static String formatWithoutSymbol(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return new DecimalFormat(MONEY_PATTERN).format(amount);
    }

    /**
     * 元转分（用于支付接口）
     *
     * <p>示例：12.34元 -> 1234分
     *
     * @param yuan 元
     * @return 分；为兼容旧调用，null 仍返回零，新金额契约不接受 null
     * @throws ArithmeticException 存在分以下非零小数或结果超出 long 范围
     */
    public static Long yuanToFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(new BigDecimal("100")).longValueExact();
    }

    /**
     * 分转元
     *
     * <p>示例：1234分 -> 12.34元
     *
     * @param fen 分
     * @return 元
     */
    public static BigDecimal fenToYuan(Long fen) {
        if (fen == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(fen).divide(new BigDecimal("100"), DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    /**
     * 判断是否为正数
     *
     * @param amount 金额
     * @return true正数，false非正数
     */
    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 判断是否为负数
     *
     * @param amount 金额
     * @return true负数，false非负数
     */
    public static boolean isNegative(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 判断是否为零
     *
     * @param amount 金额
     * @return true为零，false不为零
     */
    public static boolean isZero(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * 比较大小
     *
     * @param a 金额a
     * @param b 金额b
     * @return -1: a<b, 0: a=b, 1: a>b
     */
    public static int compare(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        if (b == null) {
            b = BigDecimal.ZERO;
        }
        return a.compareTo(b);
    }

    /**
     * 取最大值
     *
     * @param a 金额a
     * @param b 金额b
     * @return 按 null 视为零比较后的原始参数，可能为 null
     */
    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return compare(a, b) >= 0 ? a : b;
    }

    /**
     * 取最小值
     *
     * @param a 金额a
     * @param b 金额b
     * @return 按 null 视为零比较后的原始参数，可能为 null
     */
    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return compare(a, b) <= 0 ? a : b;
    }
}
