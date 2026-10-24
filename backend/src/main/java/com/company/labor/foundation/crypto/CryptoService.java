package com.company.labor.foundation.crypto;

import com.company.labor.foundation.FoundationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 AES-GCM 的带用途隔离的版本化加解密服务。
 * 密文携带协议版本、密钥版本和随机 nonce；用途参与认证，不能跨用途解密。
 */
@Service
public class CryptoService {
    /** 为每次加密生成独立 nonce 的安全随机源。 */
    private final SecureRandom random = new SecureRandom();
    /** 按版本索引的不可变密钥快照，旧密钥可用于解密历史密文。 */
    private final Map<String, SecretKeySpec> keys;
    /** 新密文使用的活动密钥版本。 */
    private final String activeKey;

    /**
     * 解析并校验密钥配置；未选择活动密钥时可构造服务，但不能执行加密。
     * @param properties 包含版本化 Base64 密钥的配置
     * @throws IllegalArgumentException Base64 密钥无法解码
     * @throws IllegalStateException 版本格式、密钥长度或活动版本不合法
     */
    public CryptoService(FoundationProperties properties) {
        Map<String, SecretKeySpec> configured = new HashMap<>();
        properties.getCrypto().getKeys().forEach((version, encoded) -> {
            if (!version.matches("[a-zA-Z0-9_-]{1,40}")) {
                throw new IllegalStateException("Invalid crypto key version");
            }
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalStateException("AES keys must contain 16, 24 or 32 bytes");
            }
            configured.put(version, new SecretKeySpec(key, "AES"));
        });
        keys = Map.copyOf(configured);
        activeKey = properties.getCrypto().getActiveKey();
        if (!activeKey.isBlank() && !keys.containsKey(activeKey)) {
            throw new IllegalStateException("Active encryption key is not configured");
        }
    }

    /**
     * 判断活动密钥是否已经配置。
     * @return 活动版本存在时为 true
     */
    public boolean isConfigured() {
        return keys.containsKey(activeKey);
    }

    /**
     * 使用活动密钥加密文本，输出点分隔的版本化密文，不记录明文。
     * @param plainText 非空明文，可为空字符串
     * @param purpose 非空白业务用途，解密时必须完全一致
     * @return v1、密钥版本、nonce 和认证密文组成的字符串
     * @throws IllegalStateException 未配置活动密钥或加密失败
     * @throws IllegalArgumentException 用途为空白
     * @throws NullPointerException 明文为空
     */
    public String encrypt(String plainText, String purpose) {
        if (!isConfigured()) {
            throw new IllegalStateException("Encryption is not configured");
        }
        Objects.requireNonNull(plainText, "Plaintext is required");
        requirePurpose(purpose);
        // GCM 使用 96 位随机 nonce，避免同一密钥下重复使用固定初始化向量。
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, activeKey, nonce, purpose);
            return "v1." + activeKey + "." + encode(nonce) + "."
                    + encode(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Encryption failed", exception);
        }
    }

    /**
     * 按密文中保留的密钥版本解密，并验证认证标签和业务用途。
     * @param encoded 本服务生成的版本化密文
     * @param purpose 加密时使用的业务用途
     * @return 解密后的 UTF-8 文本
     * @throws IllegalArgumentException 格式、用途、版本不合法或认证失败
     */
    public String decrypt(String encoded, String purpose) {
        requirePurpose(purpose);
        if (encoded == null) {
            throw new IllegalArgumentException("Ciphertext is required");
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v1") || !keys.containsKey(parts[1])) {
            throw new IllegalArgumentException("Unsupported ciphertext or key version");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != 12) {
                throw new IllegalArgumentException("Invalid ciphertext");
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, parts[1], nonce, purpose);
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[3])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Ciphertext authentication failed", exception);
        }
    }

    /**
     * 创建一次性 GCM 实例，将协议版本、密钥版本和用途绑定为附加认证数据。
     * @param mode 加密或解密模式
     * @param version 已配置密钥版本
     * @param nonce 本次操作的 12 字节随机数
     * @param purpose 业务用途
     * @return 使用 128 位认证标签的密码实例
     * @throws GeneralSecurityException 密码算法初始化失败
     */
    private Cipher cipher(int mode, String version, byte[] nonce, String purpose) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keys.get(version), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("v1:" + version + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    /**
     * 将二进制编码为不带填充的 URL 安全 Base64，便于组成点分隔密文。
     * @param bytes 待编码数据
     * @return Base64 文本
     */
    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 拒绝缺少用途的调用，避免不同业务字段共用无边界的加密语义。
     * @param purpose 业务用途
     * @throws IllegalArgumentException 用途为空或空白
     */
    private void requirePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Encryption purpose is required");
        }
    }
}
