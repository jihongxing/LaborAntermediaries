package com.company.labor.modules.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证密码策略拒绝短弱口令和控制字符，并用 Argon2id 慢哈希保存合法密码。 */
class PasswordCredentialEncoderTest {
    /** 验证密码长度、常见测试口令和控制字符限制。 */
    @Test
    void rejectsWeakAndControlCharacterPasswords() {
        PasswordCredentialEncoder encoder = new PasswordCredentialEncoder(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        assertThrows(IllegalArgumentException.class, () -> encoder.encode("short"));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("password123"));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("safe-value\n123"));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("x".repeat(129)));
    }

    /** 验证合法密码产生不可逆慢哈希且能被安全核验。 */
    @Test
    void storesPasswordAsArgonHash() {
        PasswordCredentialEncoder encoder = new PasswordCredentialEncoder(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        String password = "Correct-horse battery! 42";

        String encoded = encoder.encode(password);

        assertFalse(encoded.contains(password));
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches("incorrect password", encoded));
    }
}
