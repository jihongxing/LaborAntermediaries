package com.company.labor.common.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 字符串工具类
 *
 * <p>提供常用的字符串操作方法，增强版的Apache Commons Lang StringUtils。
 *
 * <p>使用示例：
 * <pre>
 * // 空判断（增强版，空白字符也算空）
 * StringUtil.isEmpty("  "); // true
 * StringUtil.isNotEmpty("hello"); // true
 *
 * // 驼峰转换
 * StringUtil.toSnakeCase("employeeName"); // employee_name
 * StringUtil.toCamelCase("employee_name"); // employeeName
 * StringUtil.toPascalCase("employee_name"); // EmployeeName
 *
 * // 首字母大小写
 * StringUtil.capitalize("hello"); // Hello
 * StringUtil.uncapitalize("Hello"); // hello
 *
 * // 字符串拼接
 * StringUtil.join(Arrays.asList("a", "b", "c"), ", "); // "a, b, c"
 *
 * // 字符串分割
 * List<String> parts = StringUtil.split("a,b,c", ","); // ["a", "b", "c"]
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class StringUtil {

    /** 禁止实例化字符串工具类。 */
    private StringUtil() {
        // 私有构造函数，防止实例化
    }

    // ==================== 空判断 ====================

    /**
     * 判断字符串是否为空（null、空字符串、空白字符）
     *
     * @param str 字符串
     * @return true为空，false不为空
     */
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 判断字符串是否不为空
     *
     * @param str 字符串
     * @return true不为空，false为空
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 判断字符串是否为空白（null、空字符串、只包含空白字符）
     *
     * @param str 字符串
     * @return true为空白，false不为空白
     */
    public static boolean isBlank(String str) {
        return isEmpty(str);
    }

    /**
     * 判断字符串是否不为空白
     *
     * @param str 字符串
     * @return true不为空白，false为空白
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    // ==================== 默认值 ====================

    /**
     * 如果字符串为空，返回默认值
     *
     * @param str 字符串
     * @param defaultStr 默认值
     * @return 字符串或默认值
     */
    public static String defaultIfEmpty(String str, String defaultStr) {
        return isEmpty(str) ? defaultStr : str;
    }

    /**
     * 如果字符串为null，返回空字符串
     *
     * @param str 字符串
     * @return 字符串或空字符串
     */
    public static String defaultString(String str) {
        return str == null ? "" : str;
    }

    // ==================== 驼峰转换 ====================

    /**
     * 驼峰转下划线（employeeName -> employee_name）
     *
     * @param camelCase 驼峰字符串
     * @return 下划线字符串
     */
    public static String toSnakeCase(String camelCase) {
        if (isEmpty(camelCase)) {
            return camelCase;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 下划线转驼峰（employee_name -> employeeName）
     *
     * @param snakeCase 下划线字符串
     * @return 驼峰字符串
     */
    public static String toCamelCase(String snakeCase) {
        if (isEmpty(snakeCase)) {
            return snakeCase;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < snakeCase.length(); i++) {
            char c = snakeCase.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else {
                if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 下划线转帕斯卡命名（employee_name -> EmployeeName）
     *
     * @param snakeCase 下划线字符串
     * @return 帕斯卡命名字符串
     */
    public static String toPascalCase(String snakeCase) {
        String camelCase = toCamelCase(snakeCase);
        return capitalize(camelCase);
    }

    // ==================== 首字母大小写 ====================

    /**
     * 首字母大写（hello -> Hello）
     *
     * @param str 字符串
     * @return 首字母大写的字符串
     */
    public static String capitalize(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * 首字母小写（Hello -> hello）
     *
     * @param str 字符串
     * @return 首字母小写的字符串
     */
    public static String uncapitalize(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    // ==================== 字符串拼接 ====================

    /**
     * 字符串拼接（带分隔符）
     *
     * @param list 字符串列表
     * @param separator 分隔符
     * @return 拼接后的字符串
     */
    public static String join(List<String> list, String separator) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(separator, list);
    }

    /**
     * 字符串拼接（带分隔符）
     *
     * @param array 字符串数组
     * @param separator 分隔符
     * @return 拼接后的字符串
     */
    public static String join(String[] array, String separator) {
        if (array == null || array.length == 0) {
            return "";
        }
        return String.join(separator, array);
    }

    // ==================== 字符串分割 ====================

    /**
     * 按字面分隔符切分，去除片段首尾空白并丢弃空片段。
     *
     * @param str 字符串
     * @param separator 分隔符
     * @return 分割后的列表
     */
    public static List<String> split(String str, String separator) {
        if (isEmpty(str)) {
            return List.of();
        }
        return Arrays.stream(str.split(Pattern.quote(separator)))
                .map(String::trim)
                .filter(StringUtil::isNotEmpty)
                .collect(Collectors.toList());
    }

    // ==================== 字符串截取 ====================

    /**
     * 截取字符串（左边）
     *
     * @param str 字符串
     * @param length 非负截取长度
     * @return 截取后的字符串
     * @throws IndexOutOfBoundsException 非空输入的截取长度为负数
     */
    public static String left(String str, int length) {
        if (isEmpty(str)) {
            return str;
        }
        if (length >= str.length()) {
            return str;
        }
        return str.substring(0, length);
    }

    /**
     * 截取字符串（右边）
     *
     * @param str 字符串
     * @param length 非负截取长度
     * @return 截取后的字符串
     * @throws IndexOutOfBoundsException 非空输入的截取长度为负数
     */
    public static String right(String str, int length) {
        if (isEmpty(str)) {
            return str;
        }
        if (length >= str.length()) {
            return str;
        }
        return str.substring(str.length() - length);
    }

    /**
     * 截取字符串（超长部分用...代替）
     *
     * @param str 字符串
     * @param maxLength 包含省略号的最大长度，需要截断时至少为 3
     * @return 截取后的字符串
     * @throws IndexOutOfBoundsException 需要截断但最大长度小于 3
     */
    public static String truncate(String str, int maxLength) {
        if (isEmpty(str)) {
            return str;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    // ==================== 字符串填充 ====================

    /**
     * 左填充（不足长度用指定字符填充）
     *
     * @param str 字符串
     * @param length 目标长度
     * @param padChar 填充字符
     * @return 填充后的字符串
     */
    public static String leftPad(String str, int length, char padChar) {
        if (str == null) {
            str = "";
        }
        if (str.length() >= length) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - str.length(); i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }

    /**
     * 右填充（不足长度用指定字符填充）
     *
     * @param str 字符串
     * @param length 目标长度
     * @param padChar 填充字符
     * @return 填充后的字符串
     */
    public static String rightPad(String str, int length, char padChar) {
        if (str == null) {
            str = "";
        }
        if (str.length() >= length) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str);
        for (int i = 0; i < length - str.length(); i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }

    // ==================== 字符串替换 ====================

    /**
     * 替换所有匹配的字符串
     *
     * @param str 原始字符串
     * @param searchStr 要查找的字符串
     * @param replacement 替换成的字符串
     * @return 替换后的字符串
     */
    public static String replaceAll(String str, String searchStr, String replacement) {
        if (isEmpty(str) || isEmpty(searchStr)) {
            return str;
        }
        return str.replace(searchStr, replacement);
    }

    // ==================== 字符串比较 ====================

    /**
     * 忽略大小写比较两个字符串是否相等
     *
     * @param str1 字符串1
     * @param str2 字符串2
     * @return true相等，false不相等
     */
    public static boolean equalsIgnoreCase(String str1, String str2) {
        if (str1 == null && str2 == null) {
            return true;
        }
        if (str1 == null || str2 == null) {
            return false;
        }
        return str1.equalsIgnoreCase(str2);
    }

    /**
     * 判断字符串是否包含子串（忽略大小写）
     *
     * @param str 字符串
     * @param searchStr 子串
     * @return true包含，false不包含
     */
    public static boolean containsIgnoreCase(String str, String searchStr) {
        if (isEmpty(str) || isEmpty(searchStr)) {
            return false;
        }
        return str.toLowerCase().contains(searchStr.toLowerCase());
    }

    // ==================== 字符串验证 ====================

    /**
     * 判断字符串是否是数字
     *
     * @param str 字符串
     * @return true是数字，false不是数字
     */
    public static boolean isNumeric(String str) {
        if (isEmpty(str)) {
            return false;
        }
        return str.matches("\\d+");
    }

    /**
     * 判断字符串是否是字母
     *
     * @param str 字符串
     * @return true是字母，false不是字母
     */
    public static boolean isAlpha(String str) {
        if (isEmpty(str)) {
            return false;
        }
        return str.matches("[a-zA-Z]+");
    }

    /**
     * 判断字符串是否是字母或数字
     *
     * @param str 字符串
     * @return true是字母或数字，false不是
     */
    public static boolean isAlphanumeric(String str) {
        if (isEmpty(str)) {
            return false;
        }
        return str.matches("[a-zA-Z0-9]+");
    }
}
