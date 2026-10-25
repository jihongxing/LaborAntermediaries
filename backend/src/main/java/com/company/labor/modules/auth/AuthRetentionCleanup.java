package com.company.labor.modules.auth;

import com.company.labor.foundation.FoundationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/** 按已批准保留期限批量清理终态验证码、过期会话和安全事件，不触碰账号及业务证据。 */
@Service
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthRetentionCleanup {
    /** 每轮最多删除记录数，避免单次任务持有过多行锁。 */
    private static final int BATCH_SIZE = 1000;
    /** 认证数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 挑战、会话和安全事件保留策略。 */
    private final FoundationProperties.Authentication settings;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建认证数据清理服务。
     * @param jdbc 认证数据库访问入口
     * @param properties 认证保留期限配置
     * @param clock 可替换 UTC 时钟
     */
    public AuthRetentionCleanup(JdbcTemplate jdbc, FoundationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = properties.getAuthentication();
        this.clock = clock;
    }

    /**
     * 每日清理一次满足最小保留期的认证终态记录；失败时由 Spring 调度器在下轮重试。
     */
    @Scheduled(fixedDelayString = "${foundation.authentication.retention-cleanup-interval-ms:86400000}")
    @Transactional
    public void cleanExpiredRecords() {
        Instant now = clock.instant();
        Instant challengeCutoff = now.minus(settings.getChallengeRetention());
        Instant sessionCutoff = now.minus(settings.getSessionRetention());
        Instant eventCutoff = now.minus(settings.getSecurityEventRetention());
        jdbc.update("""
                DELETE FROM auth_identity_binding_ticket
                WHERE id IN (
                    SELECT id FROM auth_identity_binding_ticket
                    WHERE COALESCE(consumed_at, expires_at) < ?
                    ORDER BY COALESCE(consumed_at, expires_at), id LIMIT ?
                )
                """, Timestamp.from(challengeCutoff), BATCH_SIZE);
        jdbc.update("""
                UPDATE auth_verification_challenge SET state = 'EXPIRED'
                WHERE id IN (
                    SELECT id FROM auth_verification_challenge
                    WHERE state IN ('ISSUED', 'VERIFIED') AND expires_at < ?
                    ORDER BY expires_at, id LIMIT ?
                )
                """, Timestamp.from(challengeCutoff), BATCH_SIZE);
        jdbc.update("""
                DELETE FROM auth_password_reset_ticket
                WHERE id IN (
                    SELECT id FROM auth_password_reset_ticket
                    WHERE COALESCE(consumed_at, expires_at) < ?
                    ORDER BY COALESCE(consumed_at, expires_at), id LIMIT ?
                )
                """, Timestamp.from(challengeCutoff), BATCH_SIZE);
        jdbc.update("""
                DELETE FROM auth_verification_challenge
                WHERE id IN (
                    SELECT challenge.id FROM auth_verification_challenge challenge
                    WHERE challenge.state IN ('CONSUMED', 'EXPIRED', 'LOCKED', 'CANCELLED')
                      AND COALESCE(challenge.consumed_at, challenge.expires_at) < ?
                      AND NOT EXISTS (
                          SELECT 1 FROM auth_password_reset_ticket ticket WHERE ticket.challenge_id = challenge.id
                      )
                    ORDER BY COALESCE(challenge.consumed_at, challenge.expires_at), challenge.id LIMIT ?
                )
                """, Timestamp.from(challengeCutoff), BATCH_SIZE);
        jdbc.update("""
                DELETE FROM auth_refresh_session
                WHERE id IN (
                    SELECT id FROM auth_refresh_session
                    WHERE COALESCE(revoked_at, rotated_at, expires_at) < ?
                    ORDER BY COALESCE(revoked_at, rotated_at, expires_at), id LIMIT ?
                )
                """, Timestamp.from(sessionCutoff), BATCH_SIZE);
        jdbc.update("""
                DELETE FROM auth_security_event
                WHERE id IN (
                    SELECT id FROM auth_security_event
                    WHERE created_at < ? ORDER BY created_at, id LIMIT ?
                )
                """, Timestamp.from(eventCutoff), BATCH_SIZE);
    }
}
