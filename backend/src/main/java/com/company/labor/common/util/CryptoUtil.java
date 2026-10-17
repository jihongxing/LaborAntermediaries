package com.company.labor.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 加密解密工具类
 *
 * <p>提供三种加密方式：
 * <ul>
 *   <li>BCrypt - 密码加密（不可逆，用于用户密码）</li>
 *   <li>AES - 对称加密（可逆，用于敏感信息如身份证、银行卡）</li>
 *   <li>MD5 - 摘要算法（不可逆，用于数据签名、防篡改）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 * // BCrypt密码加密
 * String encodedPassword = CryptoUtil.encodeBCrypt("123456");
 * boolean matches = CryptoUtil.matchesBCrypt("123456", encodedPassword); // true
 *
 * // AES加密敏感信息
 * String encrypted = CryptoUtil.aesEncrypt("330123199001011234");
 * String decrypted = CryptoUtil.aesDecrypt(encrypted); // 330123199001011234
 *
 * // MD5摘要
 * String md5 = CryptoUtil.md5("hello"); // 5d41402abc4b2a76b9719d911017c592
 * </pre>
 *
 * <p>安全要求：
 * <ul>
 *   <li>密码必须用BCrypt加密，禁止明文存储</li>
 *   <li>身份证、银行卡等敏感信息用AES加密后存储</li>
 *   <li>AES密钥必须从配置文件中读取，禁止硬编码</li>
 *   <li>生产环境的AES密钥必须定期更换</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
public final class CryptoUtil {

    private CryptoUtil() {
        // 私有构造函数，防止实例化
    }

    // ==================== BCrypt密码加密 ====================

    /**
     * BCrypt密码编码器
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * BCrypt加密密码
     *
     * <p>用于用户密码加密，每次加密结果都不同（自动加盐）
     *
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    public static String encodeBCrypt(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return PASSWORD_ENCODER.encode(rawPassword);
    }

    /**
     * BCrypt校验密码
     *
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return true匹配，false不匹配
     */
    public static boolean matchesBCrypt(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return PASSWORD_ENCODER.matches(rawPassword, encodedPassword);
    }

    // ==================== AES对称加密 ====================

    /**
     * AES加密算法
     */
    private static final String AES_ALGORITHM = "AES";

    /**
     * AES加密模式
     */
    private static final String AES_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /**
     * AES密钥（16字节）
     * TODO: 从配置文件中读取，生产环境必须更换
     */
    private static final String AES_KEY = "LaborSystem2024!"; // 16字节

    /**
     * AES加密
     *
     * <p>用于敏感信息加密（如身份证、银行卡），加密后可解密
     *
     * @param plainText 明文
     * @return 密文（Base64编码）
     */
    public static String aesEncrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }

        try {
            // 创建密钥
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), AES_ALGORITHM);

            // 创建密码器
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            // 加密
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Base64编码
            return Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            log.error("AES加密失败：{}", e.getMessage(), e);
            throw new RuntimeException("AES加密失败", e);
        }
    }

    /**
     * AES解密
     *
     * @param cipherText 密文（Base64编码）
     * @return 明文
     */
    public static String aesDecrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }

        try {
            // 创建密钥
            SecretKeySpec keySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), AES_ALGORITHM);

            // 创建密码器
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            // Base64解码
            byte[] encrypted = Base64.getDecoder().decode(cipherText);

            // 解密
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("AES解密失败：{}", e.getMessage(), e);
            throw new RuntimeException("AES解密失败", e);
        }
    }

    // ==================== MD5摘要 ====================

    /**
     * MD5加密
     *
     * <p>用于数据签名、防篡改，不可逆
     *
     * @param text 原始文本
     * @return MD5值（32位小写）
     */
    public static String md5(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));

            // 转换为16进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("MD5加密失败：{}", e.getMessage(), e);
            throw new RuntimeException("MD5加密失败", e);
        }
    }

    /**
     * MD5加密（带盐）
     *
     * @param text 原始文本
     * @param salt 盐值
     * @return MD5值（32位小写）
     */
    public static String md5WithSalt(String text, String salt) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        if (salt == null || salt.isEmpty()) {
            throw new IllegalArgumentException("盐值不能为空");
        }
        return md5(text + salt);
    }

    // ==================== Base64编码 ====================

    /**
     * Base64编码
     *
     * @param text 原始文本
     * @return Base64编码后的文本
     */
    public static String base64Encode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64解码
     *
     * @param base64Text Base64编码的文本
     * @return 原始文本
     */
    public static String base64Decode(String base64Text) {
        if (base64Text == null || base64Text.isEmpty()) {
            return base64Text;
        }
        byte[] decoded = Base64.getDecoder().decode(base64Text);
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
