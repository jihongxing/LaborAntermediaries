package com.company.labor.modules.auth;

import java.util.regex.Pattern;

/** 将首期支持的中国大陆手机号转换为唯一 E.164 形式，避免账号唯一约束被输入差异绕过。 */
public final class PhoneNumberNormalizer {
    /** 允许的大陆移动手机号本地格式。 */
    private static final Pattern LOCAL = Pattern.compile("1[3-9]\\d{9}");
    /** 允许的大陆手机号国际格式，空格只作为展示输入的可忽略分隔符。 */
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+861[3-9]\\d{9}");

    /** 工具类不允许实例化。 */
    private PhoneNumberNormalizer() {
    }

    /**
     * 规范化中国大陆手机号。
     * @param input 本地 11 位或带 +86 的国际格式手机号
     * @return 以 +86 开头的 E.164 手机号
     * @throws IllegalArgumentException 输入为空、格式非法或不是首期支持的大陆号码
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Phone number is required");
        }
        String compact = input.replaceAll("\\s+", "");
        if (LOCAL.matcher(compact).matches()) {
            return "+86" + compact;
        }
        if (INTERNATIONAL.matcher(compact).matches()) {
            return compact;
        }
        throw new IllegalArgumentException("Unsupported phone number");
    }
}
