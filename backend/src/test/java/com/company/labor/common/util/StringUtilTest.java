package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StringUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("字符串工具测试")
class StringUtilTest {

    @Test
    @DisplayName("空判断")
    void testIsEmpty() {
        assertTrue(StringUtil.isEmpty(null));
        assertTrue(StringUtil.isEmpty(""));
        assertTrue(StringUtil.isEmpty("   "));
        assertFalse(StringUtil.isEmpty("hello"));

        assertFalse(StringUtil.isNotEmpty(null));
        assertFalse(StringUtil.isNotEmpty(""));
        assertFalse(StringUtil.isNotEmpty("   "));
        assertTrue(StringUtil.isNotEmpty("hello"));
    }

    @Test
    @DisplayName("默认值")
    void testDefaultIfEmpty() {
        assertEquals("default", StringUtil.defaultIfEmpty(null, "default"));
        assertEquals("default", StringUtil.defaultIfEmpty("", "default"));
        assertEquals("default", StringUtil.defaultIfEmpty("   ", "default"));
        assertEquals("hello", StringUtil.defaultIfEmpty("hello", "default"));

        assertEquals("", StringUtil.defaultString(null));
        assertEquals("hello", StringUtil.defaultString("hello"));
    }

    @Test
    @DisplayName("驼峰转下划线")
    void testToSnakeCase() {
        assertEquals("employee_name", StringUtil.toSnakeCase("employeeName"));
        assertEquals("employee_basic", StringUtil.toSnakeCase("employeeBasic"));
        assertEquals("abc", StringUtil.toSnakeCase("abc"));
        assertEquals("abc_def_ghi", StringUtil.toSnakeCase("abcDefGhi"));
        assertNull(StringUtil.toSnakeCase(null));
        assertEquals("", StringUtil.toSnakeCase(""));
    }

    @Test
    @DisplayName("下划线转驼峰")
    void testToCamelCase() {
        assertEquals("employeeName", StringUtil.toCamelCase("employee_name"));
        assertEquals("employeebasic", StringUtil.toCamelCase("employeeBasic"));
        assertEquals("abc", StringUtil.toCamelCase("abc"));
        assertEquals("abcdefghi", StringUtil.toCamelCase("abc_def_ghi"));
        assertNull(StringUtil.toCamelCase(null));
        assertEquals("", StringUtil.toCamelCase(""));
    }

    @Test
    @DisplayName("下划线转帕斯卡命名")
    void testToPascalCase() {
        assertEquals("EmployeeName", StringUtil.toPascalCase("employee_name"));
        assertEquals("Employeebasic", StringUtil.toPascalCase("employeeBasic"));
        assertEquals("Abc", StringUtil.toPascalCase("abc"));
    }

    @Test
    @DisplayName("首字母大小写")
    void testCapitalize() {
        assertEquals("Hello", StringUtil.capitalize("hello"));
        assertEquals("Hello", StringUtil.capitalize("Hello"));
        assertEquals("H", StringUtil.capitalize("h"));
        assertNull(StringUtil.capitalize(null));
        assertEquals("", StringUtil.capitalize(""));

        assertEquals("hello", StringUtil.uncapitalize("Hello"));
        assertEquals("hello", StringUtil.uncapitalize("hello"));
        assertEquals("h", StringUtil.uncapitalize("H"));
        assertNull(StringUtil.uncapitalize(null));
        assertEquals("", StringUtil.uncapitalize(""));
    }

    @Test
    @DisplayName("字符串拼接")
    void testJoin() {
        List<String> list = Arrays.asList("a", "b", "c");
        assertEquals("a, b, c", StringUtil.join(list, ", "));
        assertEquals("abc", StringUtil.join(list, ""));

        String[] array = {"a", "b", "c"};
        assertEquals("a-b-c", StringUtil.join(array, "-"));

        assertEquals("", StringUtil.join((List<String>) null, ","));
        assertEquals("", StringUtil.join(List.of(), ","));
    }

    @Test
    @DisplayName("字符串分割")
    void testSplit() {
        List<String> result = StringUtil.split("a,b,c", ",");
        assertEquals(Arrays.asList("a", "b", "c"), result);

        result = StringUtil.split("a, b, c", ",");
        assertEquals(Arrays.asList("a", "b", "c"), result);

        result = StringUtil.split("", ",");
        assertEquals(List.of(), result);

        result = StringUtil.split(null, ",");
        assertEquals(List.of(), result);
    }

    @Test
    @DisplayName("字符串截取")
    void testLeftAndRight() {
        assertEquals("hel", StringUtil.left("hello", 3));
        assertEquals("hello", StringUtil.left("hello", 10));
        assertNull(StringUtil.left(null, 3));

        assertEquals("llo", StringUtil.right("hello", 3));
        assertEquals("hello", StringUtil.right("hello", 10));
        assertNull(StringUtil.right(null, 3));
    }

    @Test
    @DisplayName("字符串截断")
    void testTruncate() {
        assertEquals("hello", StringUtil.truncate("hello", 10));
        assertEquals("hel...", StringUtil.truncate("hello world", 6));
        assertNull(StringUtil.truncate(null, 10));
    }

    @Test
    @DisplayName("字符串填充")
    void testPad() {
        assertEquals("00123", StringUtil.leftPad("123", 5, '0'));
        assertEquals("123", StringUtil.leftPad("123", 3, '0'));
        assertEquals("123", StringUtil.leftPad("123", 2, '0'));

        assertEquals("12300", StringUtil.rightPad("123", 5, '0'));
        assertEquals("123", StringUtil.rightPad("123", 3, '0'));
        assertEquals("123", StringUtil.rightPad("123", 2, '0'));
    }

    @Test
    @DisplayName("字符串替换")
    void testReplaceAll() {
        assertEquals("heLLo", StringUtil.replaceAll("hello", "l", "L"));
        assertEquals("hello", StringUtil.replaceAll("hello", "x", "y"));
        assertNull(StringUtil.replaceAll(null, "a", "b"));
    }

    @Test
    @DisplayName("字符串比较")
    void testEqualsIgnoreCase() {
        assertTrue(StringUtil.equalsIgnoreCase("hello", "HELLO"));
        assertTrue(StringUtil.equalsIgnoreCase("hello", "hello"));
        assertFalse(StringUtil.equalsIgnoreCase("hello", "world"));
        assertTrue(StringUtil.equalsIgnoreCase(null, null));
        assertFalse(StringUtil.equalsIgnoreCase("hello", null));
    }

    @Test
    @DisplayName("字符串包含")
    void testContainsIgnoreCase() {
        assertTrue(StringUtil.containsIgnoreCase("hello world", "WORLD"));
        assertTrue(StringUtil.containsIgnoreCase("hello world", "hello"));
        assertFalse(StringUtil.containsIgnoreCase("hello world", "xyz"));
        assertFalse(StringUtil.containsIgnoreCase(null, "hello"));
        assertFalse(StringUtil.containsIgnoreCase("hello", null));
    }

    @Test
    @DisplayName("字符串验证")
    void testValidation() {
        assertTrue(StringUtil.isNumeric("12345"));
        assertFalse(StringUtil.isNumeric("123a5"));
        assertFalse(StringUtil.isNumeric(null));

        assertTrue(StringUtil.isAlpha("hello"));
        assertFalse(StringUtil.isAlpha("hello123"));
        assertFalse(StringUtil.isAlpha(null));

        assertTrue(StringUtil.isAlphanumeric("hello123"));
        assertFalse(StringUtil.isAlphanumeric("hello@123"));
        assertFalse(StringUtil.isAlphanumeric(null));
    }
}
