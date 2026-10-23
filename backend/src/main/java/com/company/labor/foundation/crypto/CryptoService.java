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

@Service
public class CryptoService {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKeySpec> keys;
    private final String activeKey;

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

    public boolean isConfigured() {
        return keys.containsKey(activeKey);
    }

    public String encrypt(String plainText, String purpose) {
        if (!isConfigured()) {
            throw new IllegalStateException("Encryption is not configured");
        }
        Objects.requireNonNull(plainText, "Plaintext is required");
        requirePurpose(purpose);
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

    private Cipher cipher(int mode, String version, byte[] nonce, String purpose) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keys.get(version), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("v1:" + version + ":" + purpose).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requirePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Encryption purpose is required");
        }
    }
}
