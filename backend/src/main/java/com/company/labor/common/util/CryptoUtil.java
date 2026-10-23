package com.company.labor.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public final class CryptoUtil {
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private CryptoUtil() {
    }

    public static String encodeBCrypt(String rawPassword) {
        requireText(rawPassword);
        return PASSWORD_ENCODER.encode(rawPassword);
    }

    public static boolean matchesBCrypt(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && PASSWORD_ENCODER.matches(rawPassword, encodedPassword);
    }

    public static String md5(String text) {
        requireText(text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 checksum is unavailable", exception);
        }
    }

    public static String md5WithSalt(String text, String salt) {
        requireText(text);
        requireText(salt);
        return md5(text + salt);
    }

    public static String base64Encode(String text) {
        return text == null ? null : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64Decode(String text) {
        return text == null ? null : new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static void requireText(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
    }
}
