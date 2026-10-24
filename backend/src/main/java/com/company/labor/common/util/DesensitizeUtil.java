package com.company.labor.common.util;

/**
 * 数据脱敏工具类
 *
 * <p>用于脱敏敏感信息，如身份证、手机号、银行卡、姓名等。
 * 日志输出、API响应中的敏感信息必须脱敏。
 * 本类仅进行字符遮盖，不加密、不匿名化，也不校验内容真实性；
 * 不满足方法长度条件的输入可能原样返回，调用方仍须检查输出是否适合公开。
 *
 * <p>使用示例：
 * <pre>
 * // 身份证脱敏
 * String idCard = DesensitizeUtil.idCard("330123199001011234"); // 330***********1234
 *
 * // 手机号脱敏
 * String mobile = DesensitizeUtil.mobile("13812345678"); // 138****5678
 *
 * // 银行卡脱敏
 * String bankCard = DesensitizeUtil.bankCard("6217001234567890123"); // ***************0123
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class DesensitizeUtil {

    /** 禁止实例化脱敏工具类。 */
    private DesensitizeUtil() {
        // 私有构造函数，防止实例化
    }

    /**
     * 脱敏符号
     */
    private static final char MASK_CHAR = '*';

    /**
     * 身份证脱敏
     *
     * <p>保留前3位和后4位，中间用*代替
     * <p>示例：330123199001011234 -> 330***********1234
     *
     * @param idCard 身份证号
     * @return 脱敏后的身份证号
     */
    public static String idCard(String idCard) {
        if (idCard == null || idCard.length() < 7) {
            return idCard;
        }
        return idCard.substring(0, 3) + repeatChar(MASK_CHAR, idCard.length() - 7) + idCard.substring(idCard.length() - 4);
    }

    /**
     * 手机号脱敏
     *
     * <p>保留前3位和后4位，中间用*代替
     * <p>示例：13812345678 -> 138****5678
     *
     * @param mobile 手机号
     * @return 脱敏后的手机号
     */
    public static String mobile(String mobile) {
        if (mobile == null || mobile.length() != 11) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    /**
     * 银行卡脱敏
     *
     * <p>只保留后4位，前面用*代替
     * <p>示例：6217001234567890123 -> ***************0123
     *
     * @param bankCard 银行卡号
     * @return 脱敏后的银行卡号
     */
    public static String bankCard(String bankCard) {
        if (bankCard == null || bankCard.length() < 8) {
            return bankCard;
        }
        return repeatChar(MASK_CHAR, bankCard.length() - 4) + bankCard.substring(bankCard.length() - 4);
    }

    /**
     * 姓名脱敏
     *
     * <p>只保留姓，名用*代替
     * <p>示例：张三 -> 张*，张三丰 -> 张**
     *
     * @param name 姓名
     * @return 脱敏后的姓名
     */
    public static String name(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return name.substring(0, 1) + repeatChar(MASK_CHAR, name.length() - 1);
    }

    /**
     * 邮箱脱敏
     *
     * <p>保留@前面的第1个字符和@后面的域名，中间用*代替
     * <p>示例：example@qq.com -> e******@qq.com
     *
     * @param email 邮箱
     * @return 脱敏后的邮箱
     */
    public static String email(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf('@');
        String prefix = email.substring(0, atIndex);
        String suffix = email.substring(atIndex);

        if (prefix.length() <= 1) {
            return email;
        }

        return prefix.charAt(0) + repeatChar(MASK_CHAR, prefix.length() - 1) + suffix;
    }

    /**
     * 地址脱敏
     *
     * <p>保留省市，详细地址用*代替
     * <p>示例：浙江省杭州市西湖区文三路123号 -> 浙江省杭州市********
     *
     * @param address 地址
     * @return 脱敏后的地址
     */
    public static String address(String address) {
        if (address == null || address.length() < 6) {
            return address;
        }

        // 简单处理：保留前6个字符，后面用*代替
        int keepLength = Math.min(6, address.length() / 2);
        return address.substring(0, keepLength) + repeatChar(MASK_CHAR, address.length() - keepLength);
    }

    /**
     * 密码脱敏
     *
     * <p>全部用*代替
     * <p>示例：password123 -> ***********
     *
     * @param password 密码
     * @return 脱敏后的密码
     */
    public static String password(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        return repeatChar(MASK_CHAR, password.length());
    }

    /**
     * 通用脱敏（保留前后各n个字符）
     *
     * @param str 原字符串
     * @param prefixKeep 前面保留的字符数
     * @param suffixKeep 后面保留的字符数
     * @return 脱敏后的字符串
     */
    public static String desensitize(String str, int prefixKeep, int suffixKeep) {
        if (str == null || str.length() <= (prefixKeep + suffixKeep)) {
            return str;
        }

        String prefix = str.substring(0, prefixKeep);
        String suffix = str.substring(str.length() - suffixKeep);
        int maskLength = str.length() - prefixKeep - suffixKeep;

        return prefix + repeatChar(MASK_CHAR, maskLength) + suffix;
    }

    /**
     * 重复字符
     *
     * @param ch 字符
     * @param count 重复次数
     * @return 重复后的字符串
     */
    private static String repeatChar(char ch, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
