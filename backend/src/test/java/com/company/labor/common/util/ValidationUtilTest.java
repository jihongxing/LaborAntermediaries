package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("参数校验工具类测试")
class ValidationUtilTest {

    @Test
    @DisplayName("手机号校验测试")
    void testMobile() {
        // 有效手机号
        assertTrue(ValidationUtil.isMobile("13812345678"));
        assertTrue(ValidationUtil.isMobile("15912345678"));
        assertTrue(ValidationUtil.isMobile("18812345678"));

        // 无效手机号
        assertFalse(ValidationUtil.isMobile("12812345678")); // 不是1开头
        assertFalse(ValidationUtil.isMobile("1381234567")); // 不足11位
        assertFalse(ValidationUtil.isMobile("138123456789")); // 超过11位
        assertFalse(ValidationUtil.isMobile("138abcd5678")); // 包含字母
    }

    @Test
    @DisplayName("邮箱校验测试")
    void testEmail() {
        // 有效邮箱
        assertTrue(ValidationUtil.isEmail("test@qq.com"));
        assertTrue(ValidationUtil.isEmail("test.user@example.com"));
        assertTrue(ValidationUtil.isEmail("test_user@example.co.uk"));

        // 无效邮箱
        assertFalse(ValidationUtil.isEmail("test@")); // 缺少域名
        assertFalse(ValidationUtil.isEmail("@qq.com")); // 缺少用户名
        assertFalse(ValidationUtil.isEmail("test@@qq.com")); // 多个@
    }

    @Test
    @DisplayName("身份证号校验测试")
    void testIdCard() {
        // 有效身份证号（18位）
        assertTrue(ValidationUtil.isIdCard("110101199001011234")); // 示例身份证

        // 无效身份证号
        assertFalse(ValidationUtil.isIdCard("11010119900101123")); // 不足18位
        assertFalse(ValidationUtil.isIdCard("11010119900101123X5")); // 超过18位
        assertFalse(ValidationUtil.isIdCard("11010119900101abcd")); // 包含字母（非X）
    }

    @Test
    @DisplayName("身份证提取出生日期测试")
    void testExtractBirthday() {
        String birthday = ValidationUtil.extractBirthday("110101199001011234");
        assertEquals("1990-01-01", birthday);
    }

    @Test
    @DisplayName("身份证提取性别测试")
    void testExtractGender() {
        // 倒数第2位为奇数，男性
        int gender1 = ValidationUtil.extractGender("110101199001011234");
        assertEquals(1, gender1);

        // 倒数第2位为偶数，女性
        int gender2 = ValidationUtil.extractGender("110101199001012345");
        assertEquals(2, gender2);
    }

    @Test
    @DisplayName("银行卡号校验测试")
    void testBankCard() {
        // Luhn算法测试（这里用示例卡号）
        assertTrue(ValidationUtil.isBankCard("6217001234567890123"));

        // 无效银行卡号
        assertFalse(ValidationUtil.isBankCard("621700123456789")); // 不足16位
        assertFalse(ValidationUtil.isBankCard("62170012345678901234567890")); // 超过19位
    }

    @Test
    @DisplayName("非空校验测试")
    void testNotBlank() {
        assertTrue(ValidationUtil.isNotBlank("test"));
        assertFalse(ValidationUtil.isNotBlank(""));
        assertFalse(ValidationUtil.isNotBlank("   "));
        assertFalse(ValidationUtil.isNotBlank(null));
    }

    @Test
    @DisplayName("非空校验异常测试")
    void testValidateNotBlank() {
        assertDoesNotThrow(() -> {
            ValidationUtil.validateNotBlank("test", "不能为空");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtil.validateNotBlank("", "不能为空");
        });
    }

    @Test
    @DisplayName("范围校验测试")
    void testValidateRange() {
        assertDoesNotThrow(() -> {
            ValidationUtil.validateRange(50, 1, 100, "超出范围");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtil.validateRange(101, 1, 100, "超出范围");
        });
    }

    @Test
    @DisplayName("长度校验测试")
    void testValidateLength() {
        assertDoesNotThrow(() -> {
            ValidationUtil.validateLength("test", 1, 10, "长度错误");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtil.validateLength("test", 5, 10, "长度错误");
        });
    }
}
