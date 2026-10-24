package com.company.labor.foundation.snapshot;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.access.ResourceAccessGuard;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.web.JsonCodec;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 为明确授权的协作资源保存只追加的版本快照，推进版本、保存内容及审计同库提交。
 * 不判定合同生效、规则批准或履约事实；禁止直接保存证件、账号等敏感明文。
 * SHA-256 仅检测内容异常，不是电子签章、公证或抵御数据库管理员篡改的证据机制。
 */
@Service
public class VersionedSnapshots {
    /** 读取快照所需的精确操作权限，仍须资源策略授权。 */
    public static final String READ_PERMISSION = "foundation:snapshots:read";
    /** 追加快照所需的精确操作权限，仍须资源策略授权。 */
    public static final String WRITE_PERMISSION = "foundation:snapshots:write";
    /** 单份序列化快照最大 64 KiB，避免把原始大文件放入技术表。 */
    private static final int MAX_BYTES = 64 * 1024;
    /** 仅由本服务按资源限域访问的技术表入口。 */
    private final JdbcTemplate jdbc;
    /** 生成存储文本的稳定 JSON 编码器。 */
    private final JsonCodec json;
    /** 业务模块提供的当前资源访问授权。 */
    private final ResourceAccessGuard access;
    /** 同一事务内追加版本的审计入口。 */
    private final AuditRecorder audit;
    /** 将头版本、内容及审计作为一个原子数据库操作。 */
    private final TransactionTemplate transaction;

    /**
     * 已保存版本的不可变内容封套，不暴露其他资源的版本列表。
     * @param resource 全平台资源引用
     * @param revision 从 1 开始的资源版本
     * @param schemaKey 内容结构的稳定标识，不代表业务已批准
     * @param schemaVersion 内容结构版本
     * @param contentJson 原样保存的 JSON 文本，不包含敏感明文
     * @param sha256 内容及封套关键标识的完整性摘要
     * @param actorId 追加版本时的操作者
     * @param organizationId 追加版本时的组织，不等于所有可读参与组织
     * @param createdAt 数据库记录的创建时刻
     */
    public record Snapshot(ResourceRef resource, long revision, String schemaKey, int schemaVersion,
                           String contentJson, String sha256, long actorId, long organizationId, Instant createdAt) {
    }

    /**
     * 创建版本快照服务，不自行登记任何资源放行策略。
     * @param jdbc 数据库访问入口
     * @param json JSON 编码器
     * @param access 资源授权守卫
     * @param audit 审计记录器
     * @param manager 与业务库相同的事务管理器
     */
    public VersionedSnapshots(JdbcTemplate jdbc, JsonCodec json, ResourceAccessGuard access,
                              AuditRecorder audit, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * 基于调用方看到的版本追加快照，expectedRevision 为零表示首次创建。
     * 先校验资源授权，再原子推进版本；不能用新快照替代业务批准、双方确认或幂等校验。
     * @param resource 已登记策略且类型内全平台唯一的资源
     * @param expectedRevision 调用方所依据的最新版本
     * @param schemaKey 结构标识，最多 100 位
     * @param schemaVersion 正数结构版本，结构变更须显式升级
     * @param content 不含敏感明文的非空内容
     * @return 已持久化的新版本
     * @throws IllegalArgumentException 结构标识、版本或内容不合法
     * @throws BusinessException 未授权、版本冲突或内容超过限制
     */
    public Snapshot append(ResourceRef resource, long expectedRevision, String schemaKey, int schemaVersion, Object content) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE || schemaVersion <= 0
                || schemaKey == null || !schemaKey.matches("[a-zA-Z0-9:._-]{1,100}") || content == null) {
            throw new IllegalArgumentException("Invalid snapshot schema, revision or content");
        }
        return transaction.execute(status -> {
            ExecutionContext.Identity identity = access.requireAccess(resource, WRITE_PERMISSION);
            long organization = ExecutionContext.requireTenant();
            String encoded = json.canonical(content);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new BusinessException(PlatformError.PAYLOAD_TOO_LARGE);
            }
            long revision = expectedRevision + 1;
            // 头记录的条件更新是并发控制点；并发首建也只有一个事务能获得版本 1。
            int changed = expectedRevision == 0
                    ? jdbc.update("""
                            INSERT INTO foundation_snapshot_head(resource_type, resource_id, revision)
                            VALUES (?, ?, 1) ON CONFLICT DO NOTHING
                            """, resource.type(), resource.id())
                    : jdbc.update("""
                            UPDATE foundation_snapshot_head SET revision = revision + 1
                            WHERE resource_type = ? AND resource_id = ? AND revision = ?
                            """, resource.type(), resource.id(), expectedRevision);
            if (changed != 1) {
                throw new BusinessException(PlatformError.VERSION_CONFLICT);
            }
            String hash = digest(resource, revision, schemaKey, schemaVersion, encoded, identity.actorId(), organization);
            Timestamp created = jdbc.queryForObject("""
                    INSERT INTO foundation_snapshot(resource_type, resource_id, revision, schema_key, schema_version,
                        content_json, sha256, actor_id, tenant_id, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING created_at
                    """, Timestamp.class, resource.type(), resource.id(), revision, schemaKey, schemaVersion,
                    encoded, hash, identity.actorId(), organization, MDC.get("requestId"));
            if (created == null) {
                throw new IllegalStateException("Snapshot was not persisted");
            }
            audit.record("snapshot.append", resource.type(), resource.id(), "SUCCESS");
            return new Snapshot(resource, revision, schemaKey, schemaVersion, encoded, hash,
                    identity.actorId(), organization, created.toInstant());
        });
    }

    /**
     * 每次读取重新校验当前授权并验证封套摘要，不因掌握历史版本号就默认可见。
     * 当前权限撤销后不能继续读取；跨组织范围由业务策略决定，不修改线程组织身份。
     * @param resource 指定资源
     * @param revision 正数历史版本
     * @return 完整性检查通过的快照
     * @throws IllegalArgumentException 版本不是正数
     * @throws BusinessException 未授权、资源版本不存在或完整性检查失败
     */
    public Snapshot read(ResourceRef resource, long revision) {
        if (revision <= 0) {
            throw new IllegalArgumentException("Snapshot revision must be positive");
        }
        access.requireAccess(resource, READ_PERMISSION);
        ExecutionContext.requireTenant();
        // 故意不按创建组织筛选，但必须先通过当前资源策略；不得将技术表登记为全局共享业务表。
        List<Snapshot> found = jdbc.query("""
                SELECT schema_key, schema_version, content_json, sha256, actor_id, tenant_id, created_at
                FROM foundation_snapshot WHERE resource_type = ? AND resource_id = ? AND revision = ?
                """, (row, index) -> new Snapshot(resource, revision, row.getString("schema_key"), row.getInt("schema_version"),
                row.getString("content_json"), row.getString("sha256"), row.getLong("actor_id"), row.getLong("tenant_id"),
                row.getTimestamp("created_at").toInstant()), resource.type(), resource.id(), revision);
        if (found.isEmpty()) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        Snapshot snapshot = found.get(0);
        String expected = digest(resource, revision, snapshot.schemaKey(), snapshot.schemaVersion(), snapshot.contentJson(),
                snapshot.actorId(), snapshot.organizationId());
        if (!expected.equals(snapshot.sha256())) {
            throw new BusinessException(PlatformError.INTEGRITY_FAILURE);
        }
        return snapshot;
    }

    /**
     * 将资源、版本、结构及存储文本绑定到摘要，标识不允许换行，分隔不会产生歧义。
     * @param resource 资源引用
     * @param revision 资源版本
     * @param schemaKey 结构标识
     * @param schemaVersion 结构版本
     * @param content 原样保存的 JSON 文本
     * @param actorId 创建版本的操作者
     * @param organizationId 创建版本的组织
     * @return SHA-256 小写十六进制摘要
     * @throws IllegalStateException 运行环境不支持 SHA-256
     */
    private String digest(ResourceRef resource, long revision, String schemaKey, int schemaVersion,
                          String content, long actorId, long organizationId) {
        String envelope = resource.type() + "\n" + resource.id() + "\n" + revision + "\n"
                + schemaKey + "\n" + schemaVersion + "\n" + actorId + "\n" + organizationId + "\n" + content;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(envelope.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
