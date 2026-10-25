package com.company.labor.modules.auth;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.Set;

/** 统一执行获批的密码长度与弱口令策略，并委托 Argon2id 保存密码慢哈希。 */
public final class PasswordCredentialEncoder {
    /** 首期明确拒绝的常见演示与测试密码。 */
    private static final Set<String> BLOCKED_PASSWORDS = Set.of(
            "password", "password123", "12345678", "123456789", "qwerty123", "admin123", "test1234");
    /** Spring Security 提供的 Argon2id 密码编码器。 */
    private final PasswordEncoder delegate;

    /**
     * 创建密码编码器包装，保持存储算法由 Spring Security 实现。
     * @param delegate 使用 Argon2id 参数配置的密码编码器
     */
    public PasswordCredentialEncoder(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    /**
     * 校验密码政策后计算 Argon2id 慢哈希。
     * @param rawPassword 用户设置的密码
     * @return 带算法标识和随机盐的编码凭据
     * @throws IllegalArgumentException 密码长度不在 8 至 128 个字符之间、为空白、含控制字符或属于常见弱口令
     */
    public String encode(String rawPassword) {
        validate(rawPassword);
        return delegate.encode(rawPassword);
    }

    /**
     * 核验明文输入与数据库编码凭据是否匹配，不向外暴露摘要比较细节。
     * @param rawPassword 登录输入的原始密码
     * @param encodedPassword 数据库中的慢哈希
     * @return 两者匹配且参数非空时为 true
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && delegate.matches(rawPassword, encodedPassword);
    }

    /**
     * 执行密码长度、空白、控制字符和常见弱口令检查。
     * @param rawPassword 用户设置的密码
     * @throws IllegalArgumentException 密码不符合最低安全政策
     */
    private void validate(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("Password is required");
        }
        int length = rawPassword.codePointCount(0, rawPassword.length());
        String normalized = rawPassword.toLowerCase(Locale.ROOT);
        if (length < 8 || length > 128 || rawPassword.isBlank() || BLOCKED_PASSWORDS.contains(normalized)
                || rawPassword.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password does not meet the account policy");
        }
    }
}
