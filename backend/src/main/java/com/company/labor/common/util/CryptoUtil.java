package com.company.labor.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 提供密码散列及兼容性摘要、编码工具。
 *
 * <p>密码使用 BCrypt；MD5 仅适合兼容性校验，Base64 只是可逆编码，二者均不能用于密码保护。
 */
public final class CryptoUtil {
    /** 复用默认强度的 BCrypt 编码器，每次编码自行生成随机盐。 */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /** 禁止实例化静态工具类。 */
    private CryptoUtil() {
    }

    /**
     * 将非空密码转换为不可逆的 BCrypt 散列，不记录原始密码。
     *
     * @param rawPassword 原始密码，不能为 null 或空串
     * @return 含算法参数和随机盐的密码散列
     * @throws IllegalArgumentException 密码为空或编码器拒绝该输入
     */
    public static String encodeBCrypt(String rawPassword) {
        requireText(rawPassword);
        return PASSWORD_ENCODER.encode(rawPassword);
    }

    /**
     * 校验原始密码与 BCrypt 散列是否匹配，不通过重新编码比较随机盐结果。
     *
     * @param rawPassword 原始密码
     * @param encodedPassword 已保存的 BCrypt 散列
     * @return 密码匹配时为 true，任一参数为 null 时为 false
     */
    public static boolean matchesBCrypt(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && PASSWORD_ENCODER.matches(rawPassword, encodedPassword);
    }

    /**
     * 计算 UTF-8 文本的 MD5 小写十六进制摘要，仅供兼容性校验。
     *
     * @param text 非 null 且非空的文本，允许仅含空白
     * @return MD5 摘要
     * @throws IllegalArgumentException 文本为 null 或空串
     * @throws IllegalStateException 运行环境不支持 MD5
     */
    public static String md5(String text) {
        requireText(text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 checksum is unavailable", exception);
        }
    }

    /**
     * 将文本与盐直接拼接后计算 MD5；不是安全密码派生算法或 HMAC。
     *
     * @param text 非 null 且非空的原始文本
     * @param salt 非 null 且非空的拼接后缀
     * @return 拼接文本的 MD5 摘要
     * @throws IllegalArgumentException 任一参数为 null 或空串
     * @throws IllegalStateException 运行环境不支持 MD5
     */
    public static String md5WithSalt(String text, String salt) {
        requireText(text);
        requireText(salt);
        return md5(text + salt);
    }

    /**
     * 将 UTF-8 文本转换为标准 Base64，不提供保密性。
     *
     * @param text 原始文本，可为 null
     * @return 编码结果，输入为 null 时返回 null
     */
    public static String base64Encode(String text) {
        return text == null ? null : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将标准 Base64 解码为 UTF-8 文本。
     *
     * @param text Base64 文本，可为 null
     * @return 解码结果，输入为 null 时返回 null
     * @throws IllegalArgumentException 输入不符合标准 Base64 格式
     */
    public static String base64Decode(String text) {
        return text == null ? null : new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    /**
     * 拒绝 null 和空串，但不拒绝仅含空白的字符串。
     *
     * @param value 待检查文本
     * @throws IllegalArgumentException 文本为 null 或空串
     */
    private static void requireText(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
    }
}
