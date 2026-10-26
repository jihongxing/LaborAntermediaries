package com.company.labor.foundation;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.access.ResourceAccessPolicy;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.snapshot.VersionedSnapshots;
import com.company.labor.foundation.tasks.TaskHandler;
import com.company.labor.foundation.tasks.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立 PostgreSQL 验证跨组织资源授权、版本竞争以及同库投递的真实事务边界。 */
@SpringBootTest
@Import(SupplyChainFoundationIT.Fixtures.class)
class SupplyChainFoundationIT {
    /** 合成协作资源，标识不包含真实合同或个人信息。 */
    private static final ResourceRef RESOURCE = new ResourceRef("agreement_fixture", "agreement-1");
    /** 底座操作权限仅是必要条件，具体参与关系由测试策略校验。 */
    private static final Set<String> PERMISSIONS =
            Set.of(VersionedSnapshots.READ_PERMISSION, VersionedSnapshots.WRITE_PERMISSION);
    /** 真实数据库访问入口，仅允许独立测试库。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 被测版本快照服务。 */
    @Autowired
    private VersionedSnapshots snapshots;
    /** 被测事务任务入口。 */
    @Autowired
    private TaskQueue tasks;
    /** 与业务及技术表共用的事务管理器。 */
    @Autowired
    private PlatformTransactionManager manager;

    /**
     * 复用既有集成测试的独立库校验与合成公钥，不连接默认开发库。
     * @param registry 测试专用配置入口
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FoundationIT.properties(registry);
    }

    /** 清空专用技术表，构造两个组织中各一个有效资源参与者。 */
    @BeforeEach
    void reset() {
        ExecutionContext.clear();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS resource_participant_fixture(
                    resource_id varchar(128) NOT NULL, tenant_id bigint NOT NULL, actor_id bigint NOT NULL,
                    PRIMARY KEY(resource_id, tenant_id, actor_id))
                """);
        jdbc.execute("""
                TRUNCATE platform_audit_finding_claim_request, platform_audit_finding_claim,
                    platform_audit_finding_entry, platform_audit_finding, platform_audit_query,
                    foundation_snapshot, foundation_snapshot_head, resource_participant_fixture,
                    foundation_audit, foundation_task
                """);
        FoundationIT.resetAuditChainForTest(jdbc);
        jdbc.update("INSERT INTO resource_participant_fixture VALUES (?, ?, ?), (?, ?, ?)",
                RESOURCE.id(), 11L, 7L, RESOURCE.id(), 22L, 8L);
    }

    /** 允许明确参与的另一组织读取，但同组织其他成员、未参与者和已撤销者均不可见。 */
    @Test
    void crossOrganizationAccessRequiresCurrentParticipant() {
        try (var ignored = identity(7, 11)) {
            snapshots.append(RESOURCE, 0, "fixture.terms", 1, Map.of("unitPrice", "12.34"));
        }
        try (var ignored = identity(8, 22)) {
            var snapshot = snapshots.read(RESOURCE, 1);
            assertEquals(11, snapshot.organizationId());
            assertEquals(22, ExecutionContext.requireTenant());
            assertEquals("{\"unitPrice\":\"12.34\"}", snapshot.contentJson());
        }
        try (var ignored = identity(9, 22)) {
            assertDenied(() -> snapshots.read(RESOURCE, 1));
        }
        jdbc.update("DELETE FROM resource_participant_fixture WHERE actor_id = ?", 8L);
        try (var ignored = identity(8, 22)) {
            assertDenied(() -> snapshots.read(RESOURCE, 1));
        }
        try (var ignored = identity(7, 33)) {
            assertDenied(() -> snapshots.read(RESOURCE, 1));
        }
    }

    /** 更正只能追加，旧版本不被覆盖，过期版本写入及不存在版本读取产生不同错误契约。 */
    @Test
    void historyIsAppendOnlyAndStaleWritesAreRejected() {
        try (var ignored = identity(7, 11)) {
            snapshots.append(RESOURCE, 0, "fixture.terms", 1, Map.of("quantity", 10));
            snapshots.append(RESOURCE, 1, "fixture.terms", 2, Map.of("quantity", 12));
            var conflict = assertThrows(BusinessException.class,
                    () -> snapshots.append(RESOURCE, 1, "fixture.terms", 2, Map.of("quantity", 14)));
            assertEquals(PlatformError.VERSION_CONFLICT.code(), conflict.getCode());
            assertEquals("{\"quantity\":10}", snapshots.read(RESOURCE, 1).contentJson());
            assertEquals(2, snapshots.read(RESOURCE, 2).schemaVersion());
            assertEquals(2, count("foundation_snapshot"));
            assertEquals(2, count("foundation_audit"));
            var missing = assertThrows(BusinessException.class, () -> snapshots.read(RESOURCE, 3));
            assertEquals(PlatformError.RESOURCE_NOT_FOUND.code(), missing.getCode());
        }
    }

    /**
     * 并发首建及追加分别只允许一个事务成功，不出现重复版本或多余审计。
     * @throws Exception 并发任务未能在超时时间内完成
     */
    @Test
    void concurrentWritersCannotOverwriteSameRevision() throws Exception {
        assertSingleWinner(0);
        assertSingleWinner(1);
        assertEquals(2, count("foundation_snapshot"));
        assertEquals(2, count("foundation_audit"));
        assertEquals(2L, jdbc.queryForObject("SELECT revision FROM foundation_snapshot_head", Long.class));
    }

    /** 外层业务回滚时，头版本、内容、审计及可靠任务全部回滚；无事务投递直接拒绝。 */
    @Test
    void snapshotsAndTasksRollbackWithBusinessTransaction() {
        try (var ignored = identity(7, 11)) {
            assertThrows(IllegalTransactionStateException.class,
                    () -> tasks.enqueueTransactional("supplychain.fixture", Map.of("resourceId", RESOURCE.id())));
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                snapshots.append(RESOURCE, 0, "fixture.terms", 1, Map.of("quantity", 10));
                tasks.enqueueTransactional("supplychain.fixture", Map.of("resourceId", RESOURCE.id()));
                assertEquals(1, count("foundation_task"));
                status.setRollbackOnly();
            });
        }
        assertEquals(0, count("foundation_snapshot"));
        assertEquals(0, count("foundation_snapshot_head"));
        assertEquals(0, count("foundation_audit"));
        assertEquals(0, count("foundation_task"));
    }

    /** 已有资源追加失败不得推进头版本；摘要检查也必须能发现数据库中的内容异常。 */
    @Test
    void rollbackDoesNotAdvanceHeadAndTamperingFailsClosed() {
        try (var ignored = identity(7, 11)) {
            snapshots.append(RESOURCE, 0, "fixture.terms", 1, Map.of("quantity", 10));
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                snapshots.append(RESOURCE, 1, "fixture.terms", 1, Map.of("quantity", 12));
                status.setRollbackOnly();
            });
            assertEquals(1L, jdbc.queryForObject("SELECT revision FROM foundation_snapshot_head", Long.class));
            assertEquals(1, count("foundation_audit"));
            jdbc.update("UPDATE foundation_snapshot SET content_json = ?", "{\"quantity\":999}");
            var failure = assertThrows(BusinessException.class, () -> snapshots.read(RESOURCE, 1));
            assertEquals(PlatformError.INTEGRITY_FAILURE.code(), failure.getCode());
        }
    }

    /**
     * 启动两个可信线程竞争同一预期版本，并断言恰好一个新版本提交。
     * @param expectedRevision 两个线程共同持有的旧版本
     * @throws Exception 等待超时或工作线程发生非预期异常
     */
    private void assertSingleWinner(long expectedRevision) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    try (var ignored = identity(7, 11)) {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent fixture did not start");
                        }
                        try {
                            snapshots.append(RESOURCE, expectedRevision, "fixture.terms", 1, Map.of("quantity", 10));
                            return true;
                        } catch (BusinessException exception) {
                            assertEquals(PlatformError.VERSION_CONFLICT.code(), exception.getCode());
                            return false;
                        }
                    }
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 返回合成身份作用域，测试调用方负责关闭以防线程上下文泄漏。
     * @param actor 合成成员
     * @param organization 合成组织
     * @return 可关闭的执行作用域
     */
    private static ExecutionContext.Scope identity(long actor, long organization) {
        return ExecutionContext.open(new ExecutionContext.Identity(actor, organization, PERMISSIONS), "supplychain-it");
    }

    /**
     * 断言操作被统一访问拒绝，不能因组织存在就放行。
     * @param action 待执行的读取
     */
    private void assertDenied(org.junit.jupiter.api.function.Executable action) {
        assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class, action).getCode());
    }

    /**
     * 统计固定允许的技术测试表，不接受任意标识拼接 SQL。
     * @param table 已登记的测试表名
     * @return 当前事务中可见的行数
     */
    private int count(String table) {
        if (!Set.of("foundation_snapshot", "foundation_snapshot_head", "foundation_audit", "foundation_task").contains(table)) {
            throw new IllegalArgumentException("Unexpected fixture table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 仅供测试的参与关系与任务处理器，不能成为生产默认授权。 */
    @TestConfiguration
    static class Fixtures {
        /**
         * 登记必须同时匹配资源、组织和成员的合成策略。
         * @param jdbc 测试库访问入口
         * @return 资源级测试策略
         */
        @Bean
        ResourceAccessPolicy agreementPolicy(JdbcTemplate jdbc) {
            return new ResourceAccessPolicy() {
                /**
                 * 返回仅在本测试使用的资源类型。
                 * @return 合成资源类型
                 */
                @Override
                public String resourceType() {
                    return "agreement_fixture";
                }

                /**
                 * 从可信数据库读取当前合成参与关系，不从请求中接受参与方。
                 * @param identity 当前身份
                 * @param resource 目标资源
                 * @param permission 已通过基础检查的操作权限
                 * @return 三项同时匹配时放行
                 */
                @Override
                public boolean mayAccess(ExecutionContext.Identity identity, ResourceRef resource, String permission) {
                    return jdbc.queryForObject("""
                            SELECT COUNT(*) FROM resource_participant_fixture
                            WHERE resource_id = ? AND tenant_id = ? AND actor_id = ?
                            """, Integer.class, resource.id(), identity.tenantId(), identity.actorId()) == 1;
                }
            };
        }

        /**
         * 注册无外部副作用的任务类型，用于验证事务入队。
         * @return 合成任务处理器
         */
        @Bean
        TaskHandler transactionFixtureHandler() {
            return new TaskHandler() {
                /**
                 * 返回合成任务类型。
                 * @return 合成投递类型
                 */
                @Override
                public String type() {
                    return "supplychain.fixture";
                }

                /**
                 * 不执行外部操作，测试仅验证数据库投递事务。
                 * @param taskId 任务标识
                 * @param payload 合成标识载荷
                 */
                @Override
                public void execute(long taskId, String payload) {
                }
            };
        }
    }
}
