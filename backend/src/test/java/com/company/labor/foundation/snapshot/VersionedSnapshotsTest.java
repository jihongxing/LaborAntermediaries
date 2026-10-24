package com.company.labor.foundation.snapshot;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.access.ResourceAccessGuard;
import com.company.labor.foundation.access.ResourceAccessPolicy;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.web.JsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证快照封套、授权、版本条件及失败回滚的编排；真实 SQL 并发由集成测试验证。 */
class VersionedSnapshotsTest {
    /** 模拟技术表存储，单元测试不连接数据库。 */
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    /** 模拟同库审计入口。 */
    private final AuditRecorder audit = mock(AuditRecorder.class);
    /** 模拟数据库事务边界以验证提交和回滚。 */
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    /** 模拟业务资源关系，默认允许测试参与者。 */
    private final ResourceAccessPolicy policy = mock(ResourceAccessPolicy.class);
    /** 测试中的全平台资源标识。 */
    private final ResourceRef resource = new ResourceRef("agreement", "agreement-1");
    /** 每个测试重新创建的服务。 */
    private VersionedSnapshots snapshots;

    /** 配置数据库成功写入、数据库时刻和业务授权的默认结果。 */
    @BeforeEach
    void setUp() {
        when(policy.resourceType()).thenReturn("agreement");
        when(policy.mayAccess(any(), any(), any())).thenReturn(true);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(Object[].class)))
                .thenReturn(Timestamp.from(Instant.parse("2026-10-25T00:00:00Z")));
        snapshots = new VersionedSnapshots(jdbc, new JsonCodec(new ObjectMapper()),
                new ResourceAccessGuard(List.of(policy)), audit, manager);
    }

    /**
     * 打开同时具有快照读写操作权限的当前组织身份。
     * @return 测试结束时自动恢复的身份作用域
     */
    private ExecutionContext.Scope scope() {
        return ExecutionContext.open(new ExecutionContext.Identity(7, 11L,
                Set.of(VersionedSnapshots.READ_PERMISSION, VersionedSnapshots.WRITE_PERMISSION)), "snapshot-test");
    }

    /**
     * 配置后续数据库读取的快照结果，不伪装为真实数据库测试。
     * @param values 存储返回的记录列表
     */
    private void stored(List<VersionedSnapshots.Snapshot> values) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<VersionedSnapshots.Snapshot>>any(), any(Object[].class)))
                .thenReturn(values);
    }

    /** 保存头版本、结构与正文，再在同一事务写入审计；相同正文可读回。 */
    @Test
    void appendAndReadPreserveTheExactEnvelope() {
        try (var scope = scope()) {
            var saved = snapshots.append(resource, 0, "agreement.terms", 1, Map.of("unit", "hour", "price", "1.00"));
            assertEquals(1, saved.revision());
            assertEquals(7, saved.actorId());
            assertEquals(11, saved.organizationId());
            assertEquals("{\"price\":\"1.00\",\"unit\":\"hour\"}", saved.contentJson());
            assertTrue(saved.sha256().matches("[0-9a-f]{64}"));
            stored(List.of(saved));
            assertEquals(saved, snapshots.read(resource, 1));
            verify(audit).record("snapshot.append", "agreement", "agreement-1", "SUCCESS");
            verify(manager).commit(any());
        }
    }

    /** 过期版本在数据库条件更新点失败，事务回滚且不保存正文或审计。 */
    @Test
    void staleRevisionRollsBackWithoutOverwritingHistory() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        try (var scope = scope()) {
            assertEquals(PlatformError.VERSION_CONFLICT.code(),
                    assertThrows(BusinessException.class,
                            () -> snapshots.append(resource, 2, "agreement.terms", 1, Map.of())).getCode());
        }
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        verify(jdbc, never()).queryForObject(anyString(), eq(Timestamp.class), any(Object[].class));
        verifyNoInteractions(audit);
    }

    /** 审计写入失败时不能单独提交头版本及快照。 */
    @Test
    void auditFailureRollsBackTheWholeAppend() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any(), any());
        try (var scope = scope()) {
            assertThrows(IllegalStateException.class,
                    () -> snapshots.append(resource, 0, "agreement.terms", 1, Map.of()));
        }
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
    }

    /** 未知资源策略默认拒绝，授权被撤销后不会读取历史正文。 */
    @Test
    void revokedAccessBlocksReadsBeforeStorage() {
        when(policy.mayAccess(any(), any(), any())).thenReturn(false);
        try (var scope = scope()) {
            assertThrows(BusinessException.class, () -> snapshots.read(resource, 1));
            assertThrows(BusinessException.class, () -> snapshots.append(resource, 0, "agreement.terms", 1, Map.of()));
        }
        verifyNoInteractions(jdbc, audit);
    }

    /** 没有组织上下文的身份即使策略放行，也不能保存或读取组织快照。 */
    @Test
    void missingOrganizationFailsClosed() {
        try (var scope = ExecutionContext.open(new ExecutionContext.Identity(7, null,
                Set.of(VersionedSnapshots.READ_PERMISSION, VersionedSnapshots.WRITE_PERMISSION)), "test")) {
            assertEquals(PlatformError.ORGANIZATION_REQUIRED.code(),
                    assertThrows(BusinessException.class, () -> snapshots.read(resource, 1)).getCode());
            assertThrows(BusinessException.class, () -> snapshots.append(resource, 0, "agreement.terms", 1, Map.of()));
        }
        verifyNoInteractions(jdbc, audit);
    }

    /** 超过 64 KiB 的序列化内容在推进版本前被拒绝。 */
    @Test
    void oversizedPayloadDoesNotAdvanceRevision() {
        try (var scope = scope()) {
            assertEquals(PlatformError.PAYLOAD_TOO_LARGE.code(),
                    assertThrows(BusinessException.class,
                            () -> snapshots.append(resource, 0, "agreement.terms", 1, "中".repeat(22000))).getCode());
        }
        verifyNoInteractions(jdbc, audit);
    }

    /** 原文被修改但摘要未更新时，拒绝使用该快照。 */
    @Test
    void changedContentFailsIntegrityValidation() {
        try (var scope = scope()) {
            var saved = snapshots.append(resource, 0, "agreement.terms", 1, Map.of("price", "1.00"));
            stored(List.of(new VersionedSnapshots.Snapshot(saved.resource(), saved.revision(), saved.schemaKey(),
                    saved.schemaVersion(), "{\"price\":\"999.00\"}", saved.sha256(), saved.actorId(),
                    saved.organizationId(), saved.createdAt())));
            assertEquals(PlatformError.INTEGRITY_FAILURE.code(),
                    assertThrows(BusinessException.class, () -> snapshots.read(resource, 1)).getCode());
        }
    }

    /** 不存在的版本返回稳定的不可访问错误，不返回空正文冒充快照。 */
    @Test
    void missingVersionIsNotFound() {
        stored(List.of());
        try (var scope = scope()) {
            assertEquals(PlatformError.RESOURCE_NOT_FOUND.code(),
                    assertThrows(BusinessException.class, () -> snapshots.read(resource, 1)).getCode());
        }
    }

    /** 非法版本、空内容及非法结构标识在数据库访问前失败。 */
    @Test
    void invalidSchemasAndVersionsFailBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> snapshots.append(resource, -1, "terms", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshots.append(resource, Long.MAX_VALUE, "terms", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshots.append(resource, 0, "terms", 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshots.append(resource, 0, "terms\n", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshots.append(resource, 0, "terms", 1, null));
        assertThrows(IllegalArgumentException.class, () -> snapshots.read(resource, 0));
        verifyNoInteractions(jdbc, audit, manager);
    }
}
