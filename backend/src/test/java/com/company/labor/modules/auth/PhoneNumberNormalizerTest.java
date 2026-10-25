package com.company.labor.modules.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证大陆手机号输入只会规范化为唯一的 E.164 表示。 */
class PhoneNumberNormalizerTest {
    /** 验证常见输入形式汇聚到同一手机号。 */
    @Test
    void normalizesLocalAndInternationalFormats() {
        assertEquals("+8613800138000", PhoneNumberNormalizer.normalize("13800138000"));
        assertEquals("+8613800138000", PhoneNumberNormalizer.normalize("+8613800138000"));
        assertEquals("+8613800138000", PhoneNumberNormalizer.normalize("+86 138 0013 8000"));
    }

    /** 验证非法前缀、长度、字符和海外区号被拒绝。 */
    @Test
    void rejectsUnsupportedNumbers() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberNormalizer.normalize("12800138000"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberNormalizer.normalize("1380013800"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberNormalizer.normalize("+14155552671"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberNormalizer.normalize("13800abc000"));
    }

    /** 验证国际格式与本地格式使用相同的大陆移动号码前缀规则。 */
    @Test
    void rejectsInternationalNumbersOutsideSupportedMobilePrefixes() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberNormalizer.normalize("+8612800138000"));
    }
}
