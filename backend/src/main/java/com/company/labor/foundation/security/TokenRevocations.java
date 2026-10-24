package com.company.labor.foundation.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/** 持久化 JWT 撤销记录，只将尚未到期的撤销记录视为有效拒绝依据。 */
@Service
public class TokenRevocations {
    /** 撤销记录的数据库访问入口；查询故障由认证层按拒绝访问处理。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建令牌撤销服务。
     * @param jdbc 数据库访问模板
     */
    public TokenRevocations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询令牌是否存在未到期的撤销记录。
     * @param tokenId 已通过认证声明校验的 JWT 标识
     * @return 存在有效撤销记录时为 true
     */
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM foundation_token_revocation WHERE token_id = ? AND expires_at > CURRENT_TIMESTAMP)",
                Boolean.class, tokenId));
    }

    /**
     * 保存或延长撤销有效期；重复调用不会缩短已有拒绝期限。
     * @param tokenId 非空白且最长 200 字符的 JWT 标识
     * @param expiresAt 撤销记录截止时间，通常对应令牌到期时间
     * @throws IllegalArgumentException 标识或到期时间不合法
     */
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || tokenId.length() > 200 || expiresAt == null) {
            throw new IllegalArgumentException("A token identifier and expiry are required");
        }
        // 并发重复撤销取较晚截止时间，避免旧请求覆盖新请求的有效期。
        jdbc.update("""
                INSERT INTO foundation_token_revocation(token_id, expires_at) VALUES (?, ?)
                ON CONFLICT (token_id) DO UPDATE
                SET expires_at = GREATEST(foundation_token_revocation.expires_at, EXCLUDED.expires_at)
                """, tokenId, Timestamp.from(expiresAt));
    }
}
