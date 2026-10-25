package com.company.labor.modules.auth;

import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.crypto.CryptoService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证认证秘密使用带密钥摘要或受用途隔离加密，随机凭证不重复。 */
class AuthCryptographyTest {
    /** 验证手机号摘要稳定且加密字段不能从数据库明文还原。 */
    @Test
    void hashesNormalizedPhoneAndEncryptsItsRecoverableForm() {
        FoundationProperties properties = properties("a sufficiently long test-only hmac key");
        CryptoService crypto = new CryptoService(properties);
        AuthCryptography cryptography = new AuthCryptography(properties.getAuthentication(), crypto);

        assertEquals(cryptography.hashPhone("13800138000"), cryptography.hashPhone("+86 138 0013 8000"));
        assertNotEquals("+8613800138000", cryptography.hashPhone("13800138000"));
        assertEquals("+8613800138000", crypto.decrypt(cryptography.encryptPhone("13800138000"), "auth.phone"));
    }

    /** 验证随机会话凭证具有 256 位熵且短信码始终为六位数字。 */
    @Test
    void createsOpaqueTokensAndSixDigitVerificationCodes() {
        FoundationProperties properties = properties("another sufficiently long test-only key");
        AuthCryptography cryptography = new AuthCryptography(properties.getAuthentication(), new CryptoService(properties));
        String first = cryptography.randomToken();
        String second = cryptography.randomToken();

        assertNotEquals(first, second);
        assertEquals(32, Base64.getUrlDecoder().decode(first).length);
        assertTrue(cryptography.verificationCode().matches("\\d{6}"));
    }

    /** 构建只含测试专用密钥的认证配置，不使用真实手机号或部署凭据。 */
    private FoundationProperties properties(String hmacKey) {
        FoundationProperties properties = new FoundationProperties();
        properties.getAuthentication().setPhoneHmacKey(hmacKey);
        properties.getCrypto().setActiveKey("test");
        properties.getCrypto().setKeys(Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        return properties;
    }
}
