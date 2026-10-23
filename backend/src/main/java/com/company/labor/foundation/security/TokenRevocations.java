package com.company.labor.foundation.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class TokenRevocations {
    private final JdbcTemplate jdbc;

    public TokenRevocations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM foundation_token_revocation WHERE token_id = ? AND expires_at > CURRENT_TIMESTAMP)",
                Boolean.class, tokenId));
    }

    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || tokenId.length() > 200 || expiresAt == null) {
            throw new IllegalArgumentException("A token identifier and expiry are required");
        }
        jdbc.update("""
                INSERT INTO foundation_token_revocation(token_id, expires_at) VALUES (?, ?)
                ON CONFLICT (token_id) DO UPDATE
                SET expires_at = GREATEST(foundation_token_revocation.expires_at, EXCLUDED.expires_at)
                """, tokenId, Timestamp.from(expiresAt));
    }
}
