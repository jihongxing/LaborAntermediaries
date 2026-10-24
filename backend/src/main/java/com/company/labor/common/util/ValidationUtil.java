package com.company.labor.common.util;

import java.util.regex.Pattern;

/**
 * 参数校验工具类
 *
 * <p>用于校验常见的业务参数，如身份证、手机号、邮箱等。
 * 仅做本地格式或校验位检查，不验证号码归属、证件真实性或实名关系。
 *
 * <p>使用示例：
 * <pre>
 * // 校验身份证
 * boolean valid = ValidationUtil.isIdCard("330123199001011234"); // true
 *
 * // 校验手机号
 * boolean valid = ValidationUtil.isMobile("13812345678"); // true
 *
 * // 校验并抛出异常
 * ValidationUtil.validateIdCard("330123", "身份证号格式不正确"); // 抛出IllegalArgumentException
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class ValidationUtil {

    /** 禁止实例化参数校验工具类。 */
    private ValidationUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * 手机号正则（中国大陆，1开头的11位数字）
     */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 邮箱正则
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_.-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z0-9]{2,6}$");

    /**
     * 身份证号正则（15位或18位）
     */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^(\\d{15}|\\d{17}[0-9Xx])$");

    /**
     * 身份证号权重因子
     */
    private static final int[] ID_CARD_WEIGHT = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /**
     * 身份证号校验码
     */
    private static final char[] ID_CARD_CHECK_CODE = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    // ==================== 手机号校验 ====================

    /**
     * 校验手机号
     *
     * @param mobile 手机号
     * @return true有效，false无效
     */
    public static boolean isMobile(String mobile) {
        if (mobile == null || mobile.isEmpty()) {
            return false;
        }
        return MOBILE_PATTERN.matcher(mobile).matches();
    }

    /**
     * 校验手机号（抛异常）
     *
     * @param mobile 手机号
     * @param message 错误消息
     * @throws IllegalArgumentException 手机号无效
     */
    public static void validateMobile(String mobile, String message) {
        if (!isMobile(mobile)) {
            throw new IllegalArgumentException(message);
        }
    }

    // ==================== 邮箱校验 ====================

    /**
     * 校验邮箱
     *
     * @param email 邮箱
     * @return true有效，false无效
     */
    public static boolean isEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * 校验邮箱（抛异常）
     *
     * @param email 邮箱
     * @param message 错误消息
     * @throws IllegalArgumentException 邮箱无效
     */
    public static void validateEmail(String email, String message) {
        if (!isEmail(email)) {
            throw new IllegalArgumentException(message);
        }
    }

    // ==================== 身份证号校验 ====================

    /**
     * 校验身份证号（支持15位和18位）
     *
     * <p>15 位仅检查格式，18 位额外检查校验位；不检查出生日期和行政区划真实性。
     *
     * @param idCard 身份证号
     * @return true有效，false无效
     */
    public static boolean isIdCard(String idCard) {
        if (idCard == null || idCard.isEmpty()) {
            return false;
        }

        // 格式校验
        if (!ID_CARD_PATTERN.matcher(idCard).matches()) {
            return false;
        }

        // 15位身份证不校验校验位
        if (idCard.length() == 15) {
            return true;
        }

        // 18位身份证校验校验位
        return validateIdCard18CheckCode(idCard);
    }

    /**
     * 校验18位身份证的校验位，调用方须先确认长度及前17位均为数字。
     *
     * @param idCard 身份证号
     * @return true有效，false无效
     */
    private static boolean validateIdCard18CheckCode(String idCard) {
        // 前17位
        String num17 = idCard.substring(0, 17);

        // 计算加权和
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (num17.charAt(i) - '0') * ID_CARD_WEIGHT[i];
        }

        // 计算校验码
        int mod = sum % 11;
        char checkCode = ID_CARD_CHECK_CODE[mod];

        // 比较校验码
        char lastChar = idCard.charAt(17);
        return lastChar == checkCode || (lastChar == 'x' && checkCode == 'X');
    }

    /**
     * 校验身份证号（抛异常）
     *
     * @param idCard 身份证号
     * @param message 错误消息
     * @throws IllegalArgumentException 身份证号无效
     */
    public static void validateIdCard(String idCard, String message) {
        if (!isIdCard(idCard)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 从身份证号中提取出生日期
     *
     * @param idCard 身份证号
     * @return 出生日期（格式：yyyy-MM-dd），身份证号无效返回null
     */
    public static String extractBirthday(String idCard) {
        if (!isIdCard(idCard)) {
            return null;
        }

        String birthday;
        if (idCard.length() == 15) {
            // 15位：出生年月日占6位，格式：yyMMdd
            birthday = "19" + idCard.substring(6, 12);
        } else {
            // 18位：出生年月日占8位，格式：yyyyMMdd
            birthday = idCard.substring(6, 14);
        }

        // 格式化为yyyy-MM-dd
        return birthday.substring(0, 4) + "-" + birthday.substring(4, 6) + "-" + birthday.substring(6, 8);
    }

    /**
     * 从身份证号中提取性别
     *
     * @param idCard 身份证号
     * @return 1男，2女，身份证号无效返回0
     */
    public static int extractGender(String idCard) {
        if (!isIdCard(idCard)) {
            return 0;
        }

        // 倒数第2位（15位身份证）或倒数第2位（18位身份证）表示性别
        int genderCode;
        if (idCard.length() == 15) {
            genderCode = idCard.charAt(14) - '0';
        } else {
            genderCode = idCard.charAt(16) - '0';
        }

        // 奇数为男，偶数为女
        return genderCode % 2 == 1 ? 1 : 2;
    }

    // ==================== 银行卡号校验 ====================

    /**
     * 校验银行卡号（Luhn算法）
     *
     * @param bankCard 银行卡号
     * @return true有效，false无效
     */
    public static boolean isBankCard(String bankCard) {
        if (bankCard == null || bankCard.isEmpty()) {
            return false;
        }

        // 长度校验（通常为16-19位）
        if (bankCard.length() < 16 || bankCard.length() > 19) {
            return false;
        }

        // 只能包含数字
        if (!bankCard.matches("\\d+")) {
            return false;
        }

        // Luhn算法校验
        return validateLuhn(bankCard);
    }

    /**
     * Luhn算法校验
     *
     * @param number 数字字符串
     * @return true有效，false无效
     */
    private static boolean validateLuhn(String number) {
        int sum = 0;
        boolean alternate = false;

        // 从右往左遍历
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';

            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum += digit;
            alternate = !alternate;
        }

        return sum % 10 == 0;
    }

    /**
     * 校验银行卡号（抛异常）
     *
     * @param bankCard 银行卡号
     * @param message 错误消息
     * @throws IllegalArgumentException 银行卡号无效
     */
    public static void validateBankCard(String bankCard, String message) {
        if (!isBankCard(bankCard)) {
            throw new IllegalArgumentException(message);
        }
    }

    // ==================== 通用校验 ====================

    /**
     * 校验字符串非空
     *
     * @param str 字符串
     * @return true非空，false空
     */
    public static boolean isNotBlank(String str) {
        return str != null && !str.trim().isEmpty();
    }

    /**
     * 校验字符串非空（抛异常）
     *
     * @param str 字符串
     * @param message 错误消息
     * @throws IllegalArgumentException 字符串为空
     */
    public static void validateNotBlank(String str, String message) {
        if (!isNotBlank(str)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验对象非空
     *
     * @param obj 对象
     * @param message 错误消息
     * @throws IllegalArgumentException 对象为空
     */
    public static void validateNotNull(Object obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验数字在范围内
     *
     * @param value 数字
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @param message 错误消息
     * @throws IllegalArgumentException 数字超出范围
     */
    public static void validateRange(long value, long min, long max, String message) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验字符串长度在范围内
     *
     * @param str 字符串
     * @param min 最小长度（包含）
     * @param max 最大长度（包含）
     * @param message 错误消息
     * @throws IllegalArgumentException 长度超出范围
     */
    public static void validateLength(String str, int min, int max, String message) {
        if (str == null || str.length() < min || str.length() > max) {
            throw new IllegalArgumentException(message);
        }
    }
}
