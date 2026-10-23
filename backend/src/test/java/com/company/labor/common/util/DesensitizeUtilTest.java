package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DesensitizeUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("数据脱敏工具类测试")
class DesensitizeUtilTest {

    @Test
    @DisplayName("身份证脱敏测试")
    void testIdCard() {
        String desensitized = DesensitizeUtil.idCard("330123199001011234");
        assertEquals("330***********1234", desensitized);
    }

    @Test
    @DisplayName("手机号脱敏测试")
    void testMobile() {
        String desensitized = DesensitizeUtil.mobile("13812345678");
        assertEquals("138****5678", desensitized);
    }

    @Test
    @DisplayName("银行卡脱敏测试")
    void testBankCard() {
        String desensitized = DesensitizeUtil.bankCard("6217001234567890123");
        assertEquals("***************0123", desensitized);
    }

    @Test
    @DisplayName("姓名脱敏测试")
    void testName() {
        String desensitized1 = DesensitizeUtil.name("张三");
        assertEquals("张*", desensitized1);

        String desensitized2 = DesensitizeUtil.name("张三丰");
        assertEquals("张**", desensitized2);
    }

    @Test
    @DisplayName("邮箱脱敏测试")
    void testEmail() {
        String desensitized = DesensitizeUtil.email("example@qq.com");
        assertEquals("e******@qq.com", desensitized);
    }

    @Test
    @DisplayName("地址脱敏测试")
    void testAddress() {
        String desensitized = DesensitizeUtil.address("浙江省杭州市西湖区文三路123号");
        assertEquals("浙江省杭州市**********", desensitized);
    }

    @Test
    @DisplayName("密码脱敏测试")
    void testPassword() {
        String desensitized = DesensitizeUtil.password("password123");
        assertEquals("***********", desensitized);
    }

    @Test
    @DisplayName("通用脱敏测试")
    void testDesensitize() {
        String desensitized = DesensitizeUtil.desensitize("1234567890", 2, 2);
        assertEquals("12******90", desensitized);
    }

    @Test
    @DisplayName("null值处理测试")
    void testNullHandling() {
        assertNull(DesensitizeUtil.idCard(null));
        assertNull(DesensitizeUtil.mobile(null));
        assertNull(DesensitizeUtil.bankCard(null));
        assertNull(DesensitizeUtil.name(null));
    }
}
