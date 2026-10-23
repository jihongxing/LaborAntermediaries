package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CryptoUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("加密解密工具测试")
class CryptoUtilTest {

    @Test
    @DisplayName("BCrypt加密密码")
    void testEncodeBCrypt() {
        String rawPassword = "123456";
        String encoded = CryptoUtil.encodeBCrypt(rawPassword);

        assertNotNull(encoded);
        assertNotEquals(rawPassword, encoded);
        assertTrue(encoded.startsWith("$2a$"));
    }

    @Test
    @DisplayName("BCrypt校验密码")
    void testMatchesBCrypt() {
        String rawPassword = "123456";
        String encoded = CryptoUtil.encodeBCrypt(rawPassword);

        assertTrue(CryptoUtil.matchesBCrypt(rawPassword, encoded));
        assertFalse(CryptoUtil.matchesBCrypt("wrong", encoded));
    }

    @Test
    @DisplayName("BCrypt每次加密结果不同")
    void testBCryptDifferentEachTime() {
        String rawPassword = "123456";
        String encoded1 = CryptoUtil.encodeBCrypt(rawPassword);
        String encoded2 = CryptoUtil.encodeBCrypt(rawPassword);

        assertNotEquals(encoded1, encoded2);
        assertTrue(CryptoUtil.matchesBCrypt(rawPassword, encoded1));
        assertTrue(CryptoUtil.matchesBCrypt(rawPassword, encoded2));
    }

    @Test
    @DisplayName("BCrypt空密码抛出异常")
    void testEncodeBCryptThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            CryptoUtil.encodeBCrypt(null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            CryptoUtil.encodeBCrypt("");
        });
    }

    @Test
    @DisplayName("MD5加密")
    void testMd5() {
        String text = "hello";
        String md5 = CryptoUtil.md5(text);

        assertNotNull(md5);
        assertEquals(32, md5.length());
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5);
    }

    @Test
    @DisplayName("MD5加密相同内容结果相同")
    void testMd5SameInput() {
        String text = "hello";
        String md51 = CryptoUtil.md5(text);
        String md52 = CryptoUtil.md5(text);

        assertEquals(md51, md52);
    }

    @Test
    @DisplayName("MD5加密不同内容结果不同")
    void testMd5DifferentInput() {
        String text1 = "hello";
        String text2 = "world";
        String md51 = CryptoUtil.md5(text1);
        String md52 = CryptoUtil.md5(text2);

        assertNotEquals(md51, md52);
    }

    @Test
    @DisplayName("MD5加密带盐")
    void testMd5WithSalt() {
        String text = "hello";
        String salt = "salt123";
        String md5 = CryptoUtil.md5WithSalt(text, salt);

        assertNotNull(md5);
        assertEquals(32, md5.length());
        assertNotEquals(CryptoUtil.md5(text), md5);
    }

    @Test
    @DisplayName("MD5空文本抛出异常")
    void testMd5ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            CryptoUtil.md5(null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            CryptoUtil.md5("");
        });
    }

    @Test
    @DisplayName("Base64编码解码")
    void testBase64EncodeAndDecode() {
        String text = "hello world";
        String encoded = CryptoUtil.base64Encode(text);
        String decoded = CryptoUtil.base64Decode(encoded);

        assertNotNull(encoded);
        assertNotEquals(text, encoded);
        assertEquals(text, decoded);
    }

    @Test
    @DisplayName("Base64编码解码中文")
    void testBase64EncodeDecodeChinese() {
        String text = "你好世界";
        String encoded = CryptoUtil.base64Encode(text);
        String decoded = CryptoUtil.base64Decode(encoded);

        assertEquals(text, decoded);
    }

    @Test
    @DisplayName("Base64编码空字符串")
    void testBase64EncodeEmpty() {
        assertNull(CryptoUtil.base64Encode(null));
        assertEquals("", CryptoUtil.base64Encode(""));
    }

    @Test
    @DisplayName("Base64解码空字符串")
    void testBase64DecodeEmpty() {
        assertNull(CryptoUtil.base64Decode(null));
        assertEquals("", CryptoUtil.base64Decode(""));
    }
}
