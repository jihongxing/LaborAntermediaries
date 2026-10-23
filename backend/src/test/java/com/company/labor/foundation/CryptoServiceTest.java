package com.company.labor.foundation;

import com.company.labor.foundation.crypto.CryptoService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoServiceTest {

    private FoundationProperties properties() {
        FoundationProperties properties = new FoundationProperties();
        properties.getCrypto().setActiveKey("key1");
        properties.getCrypto().setKeys(Map.of("key1", Base64.getEncoder().encodeToString(new byte[32])));
        return properties;
    }

    @Test
    void roundTripUsesRandomNonceAndBindsPurpose() {
        CryptoService crypto = new CryptoService(properties());
        String first = crypto.encrypt("敏感内容", "tenant:10:field");
        String second = crypto.encrypt("敏感内容", "tenant:10:field");
        assertNotEquals(first, second);
        assertEquals("敏感内容", crypto.decrypt(first, "tenant:10:field"));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(first, "tenant:20:field"));
    }

    @Test
    void tamperedCiphertextAndUnconfiguredKeysFailClosed() {
        CryptoService crypto = new CryptoService(properties());
        String encrypted = crypto.encrypt("value", "purpose");
        int lastPart = encrypted.lastIndexOf('.') + 1;
        char replacement = encrypted.charAt(lastPart) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, lastPart) + replacement + encrypted.substring(lastPart + 1);
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(tampered, "purpose"));
        assertThrows(IllegalStateException.class, () -> new CryptoService(new FoundationProperties()).encrypt("value", "purpose"));
    }

    @Test
    void oldVersionsRemainReadableAfterRotation() {
        FoundationProperties properties = properties();
        String encrypted = new CryptoService(properties).encrypt("value", "purpose");
        properties.getCrypto().setKeys(Map.of("key1", properties.getCrypto().getKeys().get("key1"),
                "key2", Base64.getEncoder().encodeToString(new byte[16])));
        properties.getCrypto().setActiveKey("key2");
        CryptoService rotated = new CryptoService(properties);
        assertEquals("value", rotated.decrypt(encrypted, "purpose"));
        assertThrows(IllegalArgumentException.class, () -> rotated.decrypt("v0.bad", "purpose"));
    }
}
