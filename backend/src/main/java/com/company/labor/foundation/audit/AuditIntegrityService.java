package com.company.labor.foundation.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** 在 PostgreSQL 一致性快照中验证只追加审计哈希链。 */
@Service
public class AuditIntegrityService {
    /** 只读查询审计事件与链头。 */
    private final JdbcTemplate jdbc;
    /** 将多条核对查询绑定到同一可重复读快照。 */
    private final PlatformTransactionManager transactionManager;

    /**
     * 创建审计完整性核对服务。
     * @param jdbc 审计数据库访问模板
     * @param transactionManager 数据库事务管理器
     */
    public AuditIntegrityService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /**
     * 核对历史边界、每条已封存事件及持久化链头。
     * @return 不包含审计正文的稳定核对结果
     */
    public IntegrityReport verify() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> verifySnapshot());
    }

    /** 在同一数据库快照内核对审计历史，结果不暴露事件内容。 */
    private IntegrityReport verifySnapshot() {
        List<ChainState> states = jdbc.query("""
                SELECT legacy_through_event_id, legacy_event_count, last_event_id, last_hash
                FROM foundation_audit_chain WHERE chain_id = 1
                """, (result, rowNumber) -> new ChainState(result.getLong("legacy_through_event_id"),
                result.getLong("legacy_event_count"), nullableLong(result, "last_event_id"),
                result.getString("last_hash")));
        if (states.size() != 1) {
            return IntegrityReport.invalid("CHAIN_STATE_MISSING", 0, 0, null);
        }
        ChainState state = states.get(0);

        long legacyEvents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE id <= ? AND event_hash IS NULL
                """, Long.class, state.legacyThroughEventId());
        if (legacyEvents != state.legacyEventCount()) {
            return IntegrityReport.invalid("LEGACY_HISTORY_CHANGED", 0, legacyEvents, null);
        }

        Integer duplicateHashes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT event_hash FROM foundation_audit
                    WHERE (id > ? OR event_hash IS NOT NULL) AND event_hash IS NOT NULL
                    GROUP BY event_hash HAVING COUNT(*) > 1
                ) duplicates
                """, Integer.class, state.legacyThroughEventId());
        if (duplicateHashes != null && duplicateHashes > 0) {
            return IntegrityReport.invalid("DUPLICATE_EVENT_HASH", 0, legacyEvents, null);
        }

        IntegrityReport chainReport = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, actor_id, actor_type, tenant_id, action, resource_type, resource_id, outcome,
                        request_id, created_at, review_case_id, appeal_case_id, finding_id, task_id,
                        resource_version, actor_role, correlation_id, reason_code, purpose_code,
                        previous_hash, event_hash
                    FROM foundation_audit
                    WHERE id > ? OR event_hash IS NOT NULL
                    ORDER BY id
                    """);
            // repeatable-read 事务关闭自动提交后，PostgreSQL 可按批拉取而不将全链装入应用内存。
            statement.setFetchSize(500);
            statement.setLong(1, state.legacyThroughEventId());
            return statement;
        }, result -> {
            String expectedPreviousHash = null;
            Long lastVerifiedId = null;
            long checkedEvents = 0;
            while (result.next()) {
                StoredEvent stored = mapEvent(result);
                checkedEvents++;
                if ((stored.event().previousHash() == null && expectedPreviousHash != null)
                        || (stored.event().previousHash() != null
                        && !stored.event().previousHash().equals(expectedPreviousHash))) {
                    return IntegrityReport.invalid("CHAIN_LINK_MISMATCH", checkedEvents, legacyEvents,
                            stored.event().id());
                }
                String calculatedHash = AuditEventHash.calculate(stored.event());
                if (!calculatedHash.equals(stored.eventHash())) {
                    return IntegrityReport.invalid("EVENT_HASH_MISMATCH", checkedEvents, legacyEvents,
                            stored.event().id());
                }
                expectedPreviousHash = stored.eventHash();
                lastVerifiedId = stored.event().id();
            }

            if (!Objects.equals(lastVerifiedId, state.lastEventId())
                    || !Objects.equals(expectedPreviousHash, state.lastHash())) {
                return IntegrityReport.invalid("CHAIN_HEAD_MISMATCH", checkedEvents, legacyEvents,
                        state.lastEventId());
            }
            return new IntegrityReport(true, checkedEvents, legacyEvents, "OK", null);
        });
        return chainReport;
    }

    /**
     * 将数据库行映射为事件摘要覆盖的字段。
     * @param result 当前审计事件行
     * @return 事件内容与持久化摘要
     * @throws SQLException 数据库字段读取失败
     */
    private StoredEvent mapEvent(ResultSet result) throws SQLException {
        Timestamp createdAt = result.getTimestamp("created_at");
        AuditEventHash.Event event = new AuditEventHash.Event(
                result.getLong("id"), nullableLong(result, "actor_id"), result.getString("actor_type"),
                nullableLong(result, "tenant_id"), result.getString("action"), result.getString("resource_type"),
                result.getString("resource_id"), result.getString("outcome"), result.getString("request_id"),
                createdAt.toInstant(), result.getString("review_case_id"), result.getString("appeal_case_id"),
                result.getString("finding_id"), result.getString("task_id"), nullableLong(result, "resource_version"),
                result.getString("actor_role"), result.getString("correlation_id"),
                result.getString("reason_code"), result.getString("purpose_code"), result.getString("previous_hash"));
        return new StoredEvent(event, result.getString("event_hash"));
    }

    /**
     * 读取可空 PostgreSQL 整数，避免把 SQL NULL 转换为零。
     * @param result 当前数据库行
     * @param column 列名
     * @return 数据库值或空值
     * @throws SQLException 数据库字段读取失败
     */
    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /** 启动链路核对时捕获的历史边界及当前可信链头。 */
    private record ChainState(long legacyThroughEventId, long legacyEventCount, Long lastEventId, String lastHash) {
    }

    /** 一条已封存审计事件及其持久化摘要。 */
    private record StoredEvent(AuditEventHash.Event event, String eventHash) {
    }

    /**
     * 完整性核对摘要，使用稳定状态码，不包含事件内容或敏感字段。
     * @param valid 整条链及历史边界是否有效
     * @param checkedEvents 已核对的新链事件数
     * @param legacyEvents 明确标识为迁移前未链化的事件数
     * @param status 稳定结果码
     * @param failingEventId 首个可定位失败事件编号，可为空
     */
    public record IntegrityReport(boolean valid, long checkedEvents, long legacyEvents,
                                  String status, Long failingEventId) {
        /**
         * 创建带稳定状态码的失败结果。
         * @param status 失败原因码
         * @param checkedEvents 已核对事件数
         * @param legacyEvents 迁移前历史事件数
         * @param failingEventId 可定位失败事件编号
         * @return 无效核对结果
         */
        private static IntegrityReport invalid(String status, long checkedEvents, long legacyEvents,
                                               Long failingEventId) {
            return new IntegrityReport(false, checkedEvents, legacyEvents, status, failingEventId);
        }
    }
}
