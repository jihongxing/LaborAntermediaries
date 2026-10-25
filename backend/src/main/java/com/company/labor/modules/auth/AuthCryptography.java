package com.company.labor.modules.auth;

import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.crypto.CryptoService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 提供认证模块使用的手机号加密、域隔离 HMAC 摘要和不可预测凭证。
 * HMAC 密钥必须独立于数据加密密钥配置，任何摘要都不能替代登录状态校验。
 */
public final class AuthCryptography {
    /** HMAC-SHA256 算法名称。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 256 位随机不透明令牌的随机字节数。 */
    private static final int TOKEN_BYTES = 32;
    /** 格式化六位验证码的十进制范围。 */
    private static final int CODE_BOUND = 1_000_000;
    /** 认证 HMAC 使用的 UTF-8 密钥材料。 */
    private final byte[] hmacKey;
    /** 用于手机号可恢复展示密文的用途隔离加密服务。 */
    private final CryptoService crypto;
    /** 为验证码和会话凭证提供密码学安全随机数。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建认证加密组件，并拒绝强度不足或未配置的手机号 HMAC 密钥。
     * @param settings 认证密钥配置
     * @param crypto 用途隔离的字段加密服务
     * @throws IllegalStateException HMAC 密钥少于 32 个 UTF-8 字节
     */
    public AuthCryptography(FoundationProperties.Authentication settings, CryptoService crypto) {
        byte[] configured = settings.getPhoneHmacKey().getBytes(StandardCharsets.UTF_8);
        if (configured.length < 32) {
            throw new IllegalStateException("Authentication HMAC key must contain at least 32 UTF-8 bytes");
        }
        this.hmacKey = configured.clone();
        this.crypto = crypto;
    }

    /**
     * 将手机号规范化后以独立 HMAC 密钥摘要，供全局唯一查找使用。
     * @param phone 中国大陆手机号
     * @return 无填充 URL 安全 Base64 编码的摘要
     */
    public String hashPhone(String phone) {
        return hash("phone:v1", PhoneNumberNormalizer.normalize(phone));
    }

    /**
     * 对验证码、IP 等敏感查找值做用途隔离摘要，避免跨字段关联。
     * @param purpose 固定用途标签，不得直接由请求提供
     * @param value 待摘要内容
     * @return 无填充 URL 安全 Base64 编码的摘要
     * @throws IllegalArgumentException 用途或内容为空
     */
    public String hash(String purpose, String value) {
        if (purpose == null || !purpose.matches("[a-zA-Z0-9:._-]{1,80}") || value == null || value.isEmpty()) {
            throw new IllegalArgumentException("A fixed purpose and non-empty value are required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Authentication digest is unavailable");
        }
    }

    /**
     * 以恒定时间比较两个已编码摘要，避免比较耗时泄露前缀信息。
     * @param expected 可信存储的摘要
     * @param supplied 请求计算出的摘要
     * @return 两个合法 URL 安全 Base64 摘要相等时为 true
     */
    public boolean matches(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(Base64.getUrlDecoder().decode(expected), Base64.getUrlDecoder().decode(supplied));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 加密规范化手机号，数据库同时保存该密文和独立 HMAC 查找键。
     * @param phone 中国大陆手机号
     * @return 绑定 auth.phone 用途的版本化密文
     */
    public String encryptPhone(String phone) {
        return crypto.encrypt(PhoneNumberNormalizer.normalize(phone), "auth.phone");
    }

    /**
     * 生成 256 位不可预测不透明令牌，调用方只应持久化其摘要。
     * @return 无填充 URL 安全 Base64 随机令牌
     */
    public String randomToken() {
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * 生成均匀分布的六位短信验证码，包含前导零。
     * @return 六位十进制字符串
     */
    public String verificationCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(CODE_BOUND));
    }
}
