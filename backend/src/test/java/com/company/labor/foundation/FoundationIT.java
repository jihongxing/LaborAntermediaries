package com.company.labor.foundation;

import com.company.labor.common.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.audit.AuditFailureLog;
import com.company.labor.foundation.audit.AuditFailureRecord;
import com.company.labor.foundation.audit.AuditFailureRecoveryService;
import com.company.labor.foundation.audit.AuditFailureRecoveryRunner;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.audit.AuditIntegrityService;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.foundation.reliability.IdempotentExecutor;
import com.company.labor.foundation.security.TokenRevocations;
import com.company.labor.foundation.storage.PrivateFileStorage;
import com.company.labor.foundation.tasks.TaskHandler;
import com.company.labor.foundation.tasks.TaskQueue;
import com.company.labor.foundation.web.JsonBodyLimitFilter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FoundationIT.Fixtures.class)
@ExtendWith(OutputCaptureExtension.class)
class FoundationIT {
    private static final Path ROOT;
    private static final Path PUBLIC_KEY;
    private static final KeyPair KEYS;

    static {
        try {
            ROOT = Files.createTempDirectory("labor-foundation-it-");
            PUBLIC_KEY = ROOT.resolve("public.pem");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            Files.writeString(PUBLIC_KEY, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test")) {
            throw new IllegalStateException("TEST_DATABASE_URL must name a dedicated PostgreSQL database ending in _test");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        registry.add("foundation.security.public-key", () -> PUBLIC_KEY.toUri().toString());
        registry.add("foundation.storage.root", () -> ROOT.resolve("files").toString());
        registry.add("foundation.storage.max-bytes", () -> 32);
        registry.add("foundation.tenant-tables[0]", () -> "isolation_fixture");
        registry.add("foundation.tasks.retry-delay", () -> "0s");
        registry.add("foundation.tasks.max-attempts", () -> 2);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc http;
    @Autowired
    private IsolationMapper mapper;
    @Autowired
    private SequenceIds ids;
    @Autowired
    private IdempotentExecutor idempotency;
    @Autowired
    private TaskQueue tasks;
    @Autowired
    private FixtureHandler handler;
    @Autowired
    private PrivateFileStorage files;
    @Autowired
    private TokenRevocations revocations;
    @Autowired
    private AuditRecorder audit;
    @Autowired
    private AuditIntegrityService auditIntegrity;
    @Autowired
    private AuditFailureLog auditFailureLog;
    @Autowired
    private AuditFailureRecoveryService auditFailureRecovery;
    @Autowired
    private PlatformTransactionManager manager;

    @BeforeEach
    void reset() throws IOException {
        ExecutionContext.clear();
        handler.calls.set(0);
        handler.fail = false;
        handler.started = null;
        handler.release = null;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS isolation_fixture(
                    id bigint PRIMARY KEY, tenant_id bigint NOT NULL, value text, version integer NOT NULL DEFAULT 0)
                """);
        jdbc.execute("ALTER TABLE isolation_fixture ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS unregistered_fixture(id bigint)");
        jdbc.execute("""
                TRUNCATE platform_audit_finding_claim_request, platform_audit_finding_claim,
                    platform_audit_finding_entry, platform_audit_finding, platform_audit_query,
                    platform_review_evidence_verification, platform_review_evidence,
                    foundation_idempotency, foundation_task, foundation_file,
                    foundation_audit, foundation_token_revocation, isolation_fixture, unregistered_fixture
                """);
        resetAuditChainForTest(jdbc);
        try (var paths = Files.list(ROOT.resolve("files"))) {
            for (Path path : paths.toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void migrationsAndHealthRunOnRealPostgres() throws Exception {
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class) > 0);
        http.perform(get("/actuator/health")).andExpect(status().isOk());
        http.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void signedIdentityCannotBeOverriddenByHeadersAndIsCleared() throws Exception {
        http.perform(get("/probe").header("Authorization", "Bearer " + token(builder -> { }))
                        .header("X-Tenant-Id", "999").header("X-Request-Id", "safe-request"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.actorId").value("7"))
                .andExpect(jsonPath("$.data.tenantId").value("11"))
                .andExpect(header().string("X-Request-Id", "safe-request"));
        assertThrows(BusinessException.class, ExecutionContext::require);
        http.perform(get("/probe")).andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'foundation:access:denied' AND actor_type = 'ANONYMOUS'
                    AND outcome = 'DENIED' AND reason_code = 'UNAUTHENTICATED'
                """, Integer.class));
        http.perform(get("/probe/permission").header("Authorization", "Bearer " + token(builder -> { })))
                .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'foundation:access:denied' AND actor_type = 'PERSON'
                    AND outcome = 'DENIED' AND reason_code = 'ACCESS_DENIED'
                """, Integer.class));
    }

    @Test
    void rejectsWrongClaimsSignatureExpiryAndRevokedTokens() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> invalid = List.of(
                builder -> builder.issuer("other"), builder -> builder.audience("other"),
                builder -> builder.audience((String) null),
                builder -> builder.expirationTime(Date.from(Instant.now().minusSeconds(300))),
                builder -> builder.jwtID(null), builder -> builder.subject("-1"),
                builder -> builder.claim("tenant_id", -1), builder -> builder.claim("tenant_id", "1.5"),
                builder -> builder.claim("permissions", "not-a-list"), builder -> builder.claim("permissions", List.of(1)),
                builder -> builder.claim("permissions", List.of("contains secret\n")));
        for (Consumer<JWTClaimsSet.Builder> change : invalid) {
            http.perform(get("/probe").header("Authorization", "Bearer " + token(change)))
                    .andExpect(status().isUnauthorized());
        }
        String revoked = token(builder -> builder.jwtID("revoked"));
        revocations.revoke("revoked", Instant.now().plusSeconds(300));
        http.perform(get("/probe").header("Authorization", "Bearer " + revoked)).andExpect(status().isUnauthorized());
        String signed = token(builder -> { });
        String[] segments = signed.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        signature[0] ^= 1;
        http.perform(get("/probe").header("Authorization", "Bearer " + segments[0] + "." + segments[1] + "."
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void httpInputErrorsAreSafeAndJsonBodiesAreBounded() throws Exception {
        String bearer = "Bearer " + token(builder -> { });
        http.perform(post("/probe").header("Authorization", bearer).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        http.perform(post("/probe").header("Authorization", bearer).contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid fields: name"));
        http.perform(put("/probe").header("Authorization", bearer)).andExpect(status().isMethodNotAllowed());
        http.perform(get("/missing").header("Authorization", bearer)).andExpect(status().isNotFound());
        http.perform(post("/probe").header("Authorization", bearer).contentType("application/json")
                        .content(new byte[JsonBodyLimitFilter.MAX_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void fileHttpEndpointsEnforcePermissionsAndReturnPrivateAttachments() throws Exception {
        MockMultipartFile upload = new MockMultipartFile("file", "../../private.txt", "text/plain",
                "private".getBytes(StandardCharsets.UTF_8));
        String readOnly = "Bearer " + token(builder -> { });
        http.perform(multipart("/foundation/files").file(upload).header("Authorization", readOnly))
                .andExpect(status().isForbidden());
        String writer = "Bearer " + token(builder -> builder.claim("permissions",
                List.of("foundation:files:write", "foundation:files:read")));
        String result = http.perform(multipart("/foundation/files").file(upload).header("Authorization", writer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.originalName").value("private.txt"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result).at("/data/id").asText();
        http.perform(get("/foundation/files/" + id).header("Authorization", readOnly))
                .andExpect(status().isOk()).andExpect(content().string("private"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Disposition"));
        http.perform(multipart("/foundation/files").header("Authorization", writer)).andExpect(status().isBadRequest());
        String unscoped = "Bearer " + token(builder -> builder.claim("tenant_id", null)
                .claim("permissions", List.of("foundation:files:read")));
        http.perform(get("/foundation/files/" + id).header("Authorization", unscoped))
                .andExpect(status().isForbidden());
    }

    @Test
    void mybatisRestrictsReadsWritesAndMissingScope() {
        assertThrows(RuntimeException.class, mapper::all);
        try (var ignored = scope(7, 11)) {
            mapper.insertFixture(1);
            assertEquals(11L, mapper.all().get(0).get("tenant_id"));
            assertThrows(RuntimeException.class, () -> mapper.explicitInsert(2, 22));
            assertThrows(RuntimeException.class, () -> mapper.move(1, 22));
            assertThrows(RuntimeException.class, mapper::unregistered);
        }
        try (var ignored = scope(7, 22)) {
            assertTrue(mapper.all().isEmpty());
            assertEquals(0, mapper.updateFixture(1));
            assertEquals(0, mapper.deleteFixture(1));
            mapper.insertFixture(2);
        }
        try (var ignored = scope(7, 11)) {
            assertEquals(1, mapper.all().size());
            assertEquals(1, mapper.updateFixture(1));
            assertEquals(1, mapper.deleteFixture(1));
        }
    }

    @Test
    void sequenceIsUniqueAcrossConcurrentConnections() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                calls.add(ids::nextId);
            }
            Set<Long> unique = new HashSet<>();
            for (var result : executor.invokeAll(calls)) {
                assertTrue(unique.add(result.get()));
            }
            assertEquals(100, unique.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void paginationAndOptimisticLockingUseTheSameIsolationBoundary() {
        try (var ignored = scope(7, 11)) {
            IsolationRecord record = new IsolationRecord();
            record.setId(1L);
            record.setValue("initial");
            record.setVersion(0);
            mapper.insert(record);
            IsolationRecord fresh = mapper.selectById(1);
            IsolationRecord stale = mapper.selectById(1);
            fresh.setValue("fresh");
            assertEquals(1, mapper.updateById(fresh));
            stale.setValue("stale");
            assertEquals(0, mapper.updateById(stale));
            Page<IsolationRecord> page = mapper.selectPage(new Page<>(1, 1000), null);
            assertEquals(200, page.getSize());
            assertEquals(1, page.getTotal());
            assertEquals("fresh", page.getRecords().get(0).getValue());
        }
        try (var ignored = scope(7, 22)) {
            assertNull(mapper.selectById(1));
            assertEquals(0, mapper.selectPage(new Page<>(1, 10), null).getTotal());
        }
    }

    @Test
    void idempotencyReplaysConflictsAndSeparatesIdentityScopes() {
        AtomicInteger calls = new AtomicInteger();
        try (var ignored = scope(7, 11)) {
            assertEquals("result-1", idempotency.execute("fixture", "key", Map.of("a", 1), String.class,
                    () -> "result-" + calls.incrementAndGet()));
            assertEquals("result-1", idempotency.execute("fixture", "key", Map.of("a", 1), String.class,
                    () -> "result-" + calls.incrementAndGet()));
            assertThrows(BusinessException.class, () -> idempotency.execute("fixture", "key", Map.of("a", 2),
                    String.class, () -> "invalid"));
        }
        try (var ignored = scope(8, 11)) {
            idempotency.execute("fixture", "key", Map.of("a", 1), String.class, () -> "result-" + calls.incrementAndGet());
        }
        try (var ignored = scope(7, 22)) {
            idempotency.execute("fixture", "key", Map.of("a", 1), String.class, () -> "result-" + calls.incrementAndGet());
        }
        assertEquals(3, calls.get());
    }

    @Test
    void idempotencyRollsBackDatabaseEffectsAndAllowsRetry() {
        try (var ignored = scope(7, 11)) {
            assertThrows(IllegalStateException.class, () -> idempotency.execute("fixture", "rollback", 1, String.class, () -> {
                mapper.insertFixture(1);
                throw new IllegalStateException("test");
            }));
            assertTrue(mapper.all().isEmpty());
            assertEquals("ok", idempotency.execute("fixture", "rollback", 1, String.class, () -> {
                mapper.insertFixture(1);
                return "ok";
            }));
        }
    }

    @Test
    void failureAuditSurvivesCallerTransactionRollback() {
        try (var ignored = scope(7, 11)) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                audit.record("foundation:test-failure", "fixture", "rollback-test", "FAILED");
                status.setRollbackOnly();
            });
        }

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'foundation:test-failure' AND resource_id = 'rollback-test'
                """, Integer.class));
        assertTrue(auditIntegrity.verify().valid());
    }

    @Test
    void businessWriteFailsClosedWhenItsAuditAppendFails(CapturedOutput output) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS audit_fail_closed_fixture(id bigint PRIMARY KEY)");
        jdbc.execute("TRUNCATE audit_fail_closed_fixture");
        jdbc.execute("""
                ALTER TABLE foundation_audit
                ADD CONSTRAINT foundation_audit_test_reject CHECK (action <> 'foundation:test-rejected')
                """);
        try {
            try (var ignored = scope(7, 11)) {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                            jdbc.update("INSERT INTO audit_fail_closed_fixture(id) VALUES (1)");
                            audit.record("foundation:test-rejected", "fixture", "fail-closed", "SUCCESS");
                        }));
            }
        } finally {
            jdbc.execute("ALTER TABLE foundation_audit DROP CONSTRAINT foundation_audit_test_reject");
        }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_fail_closed_fixture", Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit WHERE action = 'foundation:test-rejected'
                """, Integer.class));
        assertTrue(output.getOut().contains("AUDIT_APPEND_FAILURE"));

        AuditFailureRecord failure = latestAuditFailure("foundation:test-rejected", "fail-closed");
        assertEquals("SUCCESS", failure.attemptedOutcome());
        assertEquals("DataIntegrityViolationException", failure.exceptionType());
        assertEquals(AuditRecorder.RecoveryResult.BACKFILLED, auditFailureRecovery.reconcile(failure));
        assertEquals(AuditRecorder.RecoveryResult.ALREADY_RECONCILED, auditFailureRecovery.reconcile(failure));
        Map<String, Object> recovered = jdbc.queryForMap("""
                SELECT outcome, reason_code FROM foundation_audit
                WHERE action = 'foundation:test-rejected' AND resource_id = 'fail-closed'
                    AND correlation_id = ?
                """, failure.correlationId());
        assertEquals("FAILED", recovered.get("outcome"));
        assertEquals("AUDIT_APPEND_FAILURE", recovered.get("reason_code"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'foundation:audit:failure:reconciled' AND resource_id = ?
                """, Integer.class, failure.failureId()));
        AuditFailureRecoveryService.RecoveryReport report = auditFailureRecovery.reconcile();
        assertTrue(report.inspected() > 0);
        assertTrue(report.previouslyReconciled() > 0);
        new AuditFailureRecoveryRunner(auditFailureRecovery).run(new DefaultApplicationArguments(new String[0]));
        assertTrue(auditIntegrity.verify().valid());
    }

    /**
     * 事件已提交但故障通道报告不确定结果时，对账只追加核对标记，不重复补写业务事件。
     */
    @Test
    void auditRecoveryMarksAnEventThatWasAlreadyPersisted() {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        try {
            try (var ignored = scope(7, 11)) {
                audit.record("foundation:test-recovery-present", "fixture", "already-persisted", "SUCCESS");
            }
        } finally {
            MDC.remove("correlationId");
        }
        String failureId = UUID.randomUUID().toString();
        AuditFailureRecord failure = new AuditFailureRecord(failureId, Instant.now(), 7L, "PERSON", 11L,
                "foundation:test-recovery-present", "fixture", "already-persisted", "SUCCESS", "integration",
                correlationId, AuditRecorder.AuditDetails.empty(), "DataIntegrityViolationException");

        assertEquals(AuditRecorder.RecoveryResult.EVENT_ALREADY_PRESENT,
                auditFailureRecovery.reconcile(failure));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'foundation:test-recovery-present' AND resource_id = 'already-persisted'
                """, Integer.class));
        assertEquals("EVENT_ALREADY_PRESENT", jdbc.queryForObject("""
                SELECT reason_code FROM foundation_audit
                WHERE action = 'foundation:audit:failure:reconciled' AND resource_id = ?
                """, String.class, failureId));
        assertTrue(auditIntegrity.verify().valid());
    }

    /**
     * 审计数据库拒绝敏感文件读取事件时，下载流不能返回给调用方。
     * @throws Exception 文件创建和清理失败
     */
    @Test
    void sensitiveFileReadFailsClosedWhenItsAuditAppendFails() throws Exception {
        PrivateFileStorage.Metadata metadata;
        try (var ignored = scope(7, 11)) {
            metadata = files.store(new ByteArrayInputStream("private-evidence".getBytes(StandardCharsets.UTF_8)),
                    "evidence.pdf");
        }
        jdbc.execute("""
                ALTER TABLE foundation_audit
                ADD CONSTRAINT foundation_audit_test_reject_download CHECK (action <> 'file.download')
                """);
        try {
            try (var ignored = scope(7, 11)) {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> files.open(metadata.id()));
            }
        } finally {
            jdbc.execute("ALTER TABLE foundation_audit DROP CONSTRAINT foundation_audit_test_reject_download");
            Files.deleteIfExists(ROOT.resolve("files").resolve(metadata.id()));
        }

        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'file.download' AND resource_id = ?
                """, Integer.class, metadata.id()));
        AuditFailureRecord failure = latestAuditFailure("file.download", metadata.id());
        assertEquals("SUCCESS", failure.attemptedOutcome());
        assertEquals(AuditRecorder.RecoveryResult.BACKFILLED, auditFailureRecovery.reconcile(failure));
        assertTrue(auditIntegrity.verify().valid());
    }

    /** 查找持久化故障文件中最近一次匹配的资源操作记录。 */
    private AuditFailureRecord latestAuditFailure(String action, String resourceId) {
        AtomicReference<AuditFailureRecord> latest = new AtomicReference<>();
        auditFailureLog.readEach(record -> {
            if (record.action().equals(action) && record.resourceId().equals(resourceId)) {
                latest.set(record);
            }
        });
        AuditFailureRecord failure = latest.get();
        assertNotNull(failure, "Expected a durable audit failure record");
        assertTrue(Files.exists(auditFailureLog.path()), "Expected the independent failure log to exist");
        return failure;
    }

    @Test
    void auditEventsCannotBeUpdatedOrDeleted() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-immutable", "fixture", "immutable-test", "SUCCESS");
        }
        Long eventId = jdbc.queryForObject("""
                SELECT id FROM foundation_audit
                WHERE action = 'foundation:test-immutable' AND resource_id = 'immutable-test'
                """, Long.class);

        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                UPDATE foundation_audit SET outcome = 'FAILED' WHERE id = ?
                """, eventId));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                DELETE FROM foundation_audit WHERE id = ?
                """, eventId));
    }

    @Test
    void auditEventCarriesServerGeneratedCorrelationId() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-correlation", "fixture", "correlation-test", "SUCCESS");
        }

        String correlationId = jdbc.queryForObject("""
                SELECT correlation_id FROM foundation_audit
                WHERE action = 'foundation:test-correlation' AND resource_id = 'correlation-test'
                """, String.class);
        assertNotNull(correlationId);
        assertTrue(correlationId.matches("[0-9a-f-]{36}"));
    }

    @Test
    void auditEventStoresOptionalCaseVersionAndPurposeAssociations() {
        try (var ignored = scope(7, 11)) {
            audit.record("platform:case:read", "review_case", "case-1", "SUCCESS",
                    new AuditRecorder.AuditDetails("case-1", null, null, "task-1", 3L,
                            "organization-reviewer", "review", "case-review"));
        }

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT review_case_id, task_id, resource_version, actor_role, reason_code, purpose_code
                FROM foundation_audit WHERE action = 'platform:case:read' AND resource_id = 'case-1'
                """);
        assertEquals("case-1", event.get("review_case_id"));
        assertEquals("task-1", event.get("task_id"));
        assertEquals(3L, ((Number) event.get("resource_version")).longValue());
        assertEquals("organization-reviewer", event.get("actor_role"));
        assertEquals("review", event.get("reason_code"));
        assertEquals("case-review", event.get("purpose_code"));
    }

    @Test
    void auditHashChainLinksEventsAndVerifiesAgainstItsHead() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-chain", "fixture", "chain-first", "SUCCESS");
            audit.record("foundation:test-chain", "fixture", "chain-second", "SUCCESS");
        }

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT previous_hash, event_hash FROM foundation_audit
                WHERE action = 'foundation:test-chain' ORDER BY id
                """);
        assertEquals(2, events.size());
        assertNull(events.get(0).get("previous_hash"));
        assertNotNull(events.get(0).get("event_hash"));
        assertEquals(events.get(0).get("event_hash"), events.get(1).get("previous_hash"));
        assertNotNull(events.get(1).get("event_hash"));
        assertTrue(auditIntegrity.verify().valid());
    }

    @Test
    void auditIntegrityDetectsChangedEventContents() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-tamper", "fixture", "tamper-target", "SUCCESS");
        }
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_append_only");
        jdbc.update("UPDATE foundation_audit SET outcome = 'FAILED' WHERE action = 'foundation:test-tamper'");
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_append_only");

        AuditIntegrityService.IntegrityReport report = auditIntegrity.verify();
        assertFalse(report.valid());
        assertEquals("EVENT_HASH_MISMATCH", report.status());
    }

    @Test
    void auditIntegrityDetectsMissingMiddleEvent() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-gap", "fixture", "gap-first", "SUCCESS");
            audit.record("foundation:test-gap", "fixture", "gap-middle", "SUCCESS");
            audit.record("foundation:test-gap", "fixture", "gap-last", "SUCCESS");
        }
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_append_only");
        jdbc.update("DELETE FROM foundation_audit WHERE action = 'foundation:test-gap' AND resource_id = 'gap-middle'");
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_append_only");

        AuditIntegrityService.IntegrityReport report = auditIntegrity.verify();
        assertFalse(report.valid());
        assertEquals("CHAIN_LINK_MISMATCH", report.status());
    }

    @Test
    void auditIntegrityDetectsRemovedTailAgainstPersistedHead() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-tail", "fixture", "tail-target", "SUCCESS");
        }
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_append_only");
        jdbc.update("DELETE FROM foundation_audit WHERE action = 'foundation:test-tail'");
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_append_only");

        AuditIntegrityService.IntegrityReport report = auditIntegrity.verify();
        assertFalse(report.valid());
        assertEquals("CHAIN_HEAD_MISMATCH", report.status());
    }

    @Test
    void auditIntegrityDetectsDuplicateEventHashes() {
        try (var ignored = scope(7, 11)) {
            audit.record("foundation:test-duplicate", "fixture", "duplicate-source", "SUCCESS");
        }
        Long sourceId = jdbc.queryForObject("""
                SELECT id FROM foundation_audit WHERE action = 'foundation:test-duplicate'
                """, Long.class);
        Long duplicateId = jdbc.queryForObject("SELECT nextval('foundation_id_seq')", Long.class);

        jdbc.execute("DROP INDEX foundation_audit_event_hash_unique");
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_validate_append");
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_advance_chain");
        try {
            jdbc.update("""
                    INSERT INTO foundation_audit(
                        id, actor_id, actor_type, tenant_id, action, resource_type, resource_id, outcome,
                        request_id, created_at, review_case_id, appeal_case_id, finding_id, task_id,
                        resource_version, actor_role, correlation_id, reason_code, purpose_code,
                        previous_hash, event_hash)
                    SELECT ?, actor_id, actor_type, tenant_id, action, resource_type, resource_id, outcome,
                        request_id, created_at, review_case_id, appeal_case_id, finding_id, task_id,
                        resource_version, actor_role, correlation_id, reason_code, purpose_code,
                        previous_hash, event_hash
                    FROM foundation_audit WHERE id = ?
                    """, duplicateId, sourceId);
            jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_validate_append");
            jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_advance_chain");

            AuditIntegrityService.IntegrityReport report = auditIntegrity.verify();
            assertFalse(report.valid());
            assertEquals("DUPLICATE_EVENT_HASH", report.status());
        } finally {
            jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_validate_append");
            jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_advance_chain");
            jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_append_only");
            jdbc.update("DELETE FROM foundation_audit WHERE id = ?", duplicateId);
            jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_append_only");
            jdbc.execute("CREATE UNIQUE INDEX foundation_audit_event_hash_unique "
                    + "ON foundation_audit (event_hash) WHERE event_hash IS NOT NULL");
        }
    }

    @Test
    void auditIntegrityReportsLegacyHashGapAndDetectsRemovedLegacyRows() {
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_validate_append");
        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_advance_chain");
        jdbc.update("""
                INSERT INTO foundation_audit(actor_id, actor_type, tenant_id, action, resource_type, resource_id, outcome)
                VALUES (7, 'PERSON', 11, 'fixture:legacy', 'fixture', 'legacy-row', 'SUCCESS')
                """);
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_validate_append");
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_advance_chain");
        resetAuditChainForTest(jdbc);

        AuditIntegrityService.IntegrityReport baseline = auditIntegrity.verify();
        assertTrue(baseline.valid());
        assertEquals(0, baseline.checkedEvents());
        assertEquals(1, baseline.legacyEvents());

        jdbc.execute("ALTER TABLE foundation_audit DISABLE TRIGGER foundation_audit_append_only");
        jdbc.update("DELETE FROM foundation_audit WHERE action = 'fixture:legacy'");
        jdbc.execute("ALTER TABLE foundation_audit ENABLE TRIGGER foundation_audit_append_only");
        AuditIntegrityService.IntegrityReport report = auditIntegrity.verify();
        assertFalse(report.valid());
        assertEquals("LEGACY_HISTORY_CHANGED", report.status());
    }

    @Test
    void auditCatalogMarksPartialCoverageAndConnectedPlatformEvents() {
        Map<String, String> status = Map.of(
                "organization:create", jdbc.queryForObject("""
                        SELECT status FROM foundation_audit_catalog WHERE event_code = 'organization:create'
                        """, String.class),
                "platform:task:claimed", jdbc.queryForObject("""
                        SELECT status FROM foundation_audit_catalog WHERE event_code = 'platform:task:claimed'
                        """, String.class));

        assertEquals("PARTIAL", status.get("organization:create"));
        assertEquals("CONNECTED", status.get("platform:task:claimed"));
    }

    @Test
    void concurrentAuditAppendsRemainOneValidChain() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Void>> appends = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                int eventNumber = index;
                appends.add(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try (var ignored = scope(100L + eventNumber, 200L + eventNumber)) {
                        audit.record("foundation:test-concurrent", "fixture", "event-" + eventNumber, "SUCCESS");
                    }
                    return null;
                });
            }
            List<java.util.concurrent.Future<Void>> results = new ArrayList<>();
            for (Callable<Void> append : appends) {
                results.add(executor.submit(append));
            }
            start.countDown();
            for (var result : results) {
                result.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(16, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit WHERE action = 'foundation:test-concurrent'
                """, Integer.class));
        assertTrue(auditIntegrity.verify().valid());
    }

    static void resetAuditChainForTest(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE foundation_audit_chain");
        jdbc.update("""
                INSERT INTO foundation_audit_chain
                    (chain_id, legacy_through_event_id, legacy_event_count, last_event_id, last_hash)
                SELECT 1, COALESCE(MAX(id), 0), COUNT(*), NULL, NULL FROM foundation_audit
                """);
    }

    @Test
    void anonymousSecurityFailureIsRecordedWithoutInventingAnActorId() {
        audit.recordAnonymous("foundation:access:denied", "endpoint", "authentication",
                "DENIED", "UNAUTHENTICATED");

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT actor_id, actor_type, tenant_id, outcome, reason_code
                FROM foundation_audit WHERE action = 'foundation:access:denied'
                """);
        assertNull(event.get("actor_id"));
        assertEquals("ANONYMOUS", event.get("actor_type"));
        assertNull(event.get("tenant_id"));
        assertEquals("DENIED", event.get("outcome"));
        assertEquals("UNAUTHENTICATED", event.get("reason_code"));
    }

    @Test
    void concurrentIdempotencyExecutesOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        AtomicInteger calls = new AtomicInteger();
        try {
            List<Callable<String>> requests = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                requests.add(() -> {
                    try (var ignored = scope(7, 11)) {
                        return idempotency.execute("fixture", "parallel", 1, String.class,
                                () -> "result-" + calls.incrementAndGet());
                    }
                });
            }
            for (var result : executor.invokeAll(requests)) {
                assertEquals("result-1", result.get());
            }
            assertEquals(1, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void taskRetriesAreBoundedAndScopeIsRestored() {
        handler.fail = true;
        try (var ignored = scope(7, 11)) {
            long id = tasks.enqueue("fixture", Map.of("reference", 1));
            assertTrue(tasks.runNext());
            assertEquals("PENDING", tasks.status(id).state());
            assertTrue(tasks.runNext());
            assertEquals("FAILED", tasks.status(id).state());
            assertFalse(tasks.runNext());
            assertEquals(2, handler.calls.get());
            assertEquals(Set.of("fixture:permission"), ExecutionContext.require().permissions());
        }
        assertThrows(BusinessException.class, ExecutionContext::require);
    }

    @Test
    void expiredTaskLeasesAreRecoveredAndStatusIsScoped() {
        long id;
        try (var ignored = scope(7, 11)) {
            id = tasks.enqueue("fixture", "minimal");
        }
        jdbc.update("""
                UPDATE foundation_task SET status='RUNNING', lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    lease_token='dead-worker', attempts=1 WHERE id=?
                """, id);
        assertTrue(tasks.runNext());
        try (var ignored = scope(7, 11)) {
            assertEquals("SUCCEEDED", tasks.status(id).state());
            assertEquals(2, tasks.status(id).attempts());
        }
        try (var ignored = scope(8, 11)) {
            assertThrows(BusinessException.class, () -> tasks.status(id));
        }
        try (var ignored = scope(7, 22)) {
            assertThrows(BusinessException.class, () -> tasks.status(id));
        }
    }

    @Test
    void replacedLeaseCannotBeCompletedByOldWorker() throws Exception {
        handler.started = new CountDownLatch(1);
        handler.release = new CountDownLatch(1);
        long id;
        try (var ignored = scope(7, 11)) {
            id = tasks.enqueue("fixture", "minimal");
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(tasks::runNext);
            assertTrue(handler.started.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE foundation_task SET lease_token='replacement' WHERE id=?", id);
            handler.release.countDown();
            assertTrue(running.get(10, TimeUnit.SECONDS));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM foundation_task WHERE id=?", String.class, id));
        } finally {
            handler.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void taskSubmissionParticipatesInCallerTransaction() {
        try (var ignored = scope(7, 11)) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                tasks.enqueue("fixture", "minimal");
                status.setRollbackOnly();
            });
            assertFalse(tasks.runNext());
        }
    }

    @Test
    void exhaustedExpiredLeasesBecomeFailedWithoutReexecution() {
        long id;
        try (var ignored = scope(7, 11)) {
            id = tasks.enqueue("fixture", "minimal");
        }
        jdbc.update("""
                UPDATE foundation_task SET status='RUNNING', attempts=2,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?
                """, id);
        assertFalse(tasks.runNext());
        assertEquals(0, handler.calls.get());
        try (var ignored = scope(7, 11)) {
            assertEquals("FAILED", tasks.status(id).state());
        }
    }

    @Test
    void privateFilesAreReauthorizedAndNamesCannotEscapeStorage() throws Exception {
        PrivateFileStorage.Metadata metadata;
        try (var ignored = scope(7, 11)) {
            metadata = files.store(new ByteArrayInputStream("private".getBytes(StandardCharsets.UTF_8)), "../../secret.txt");
            assertEquals("secret.txt", metadata.originalName());
            try (var input = files.open(metadata.id()).stream()) {
                assertEquals("private", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertThrows(BusinessException.class, () -> files.open("../../secret.txt"));
        }
        try (var ignored = scope(8, 11)) {
            assertEquals(403, assertThrows(BusinessException.class, () -> files.open(metadata.id())).getHttpStatus());
        }
        try (var ignored = scope(7, 22)) {
            assertEquals(404, assertThrows(BusinessException.class, () -> files.open(metadata.id())).getHttpStatus());
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM foundation_audit", Integer.class));
    }

    @Test
    void oversizeAndRolledBackFilesLeaveNoMetadataOrPhysicalFiles() throws Exception {
        try (var ignored = scope(7, 11)) {
            assertEquals(413, assertThrows(BusinessException.class,
                    () -> files.store(new ByteArrayInputStream(new byte[33]), "large")).getHttpStatus());
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                files.store(new ByteArrayInputStream(new byte[3]), "rollback");
                status.setRollbackOnly();
            });
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM foundation_file", Integer.class));
        try (var paths = Files.list(ROOT.resolve("files"))) {
            assertEquals(0, paths.count());
        }
    }

    private ExecutionContext.Scope scope(long actor, long tenant) {
        return ExecutionContext.open(new ExecutionContext.Identity(actor, tenant, Set.of("fixture:permission")), "integration");
    }

    private String token(Consumer<JWTClaimsSet.Builder> change) throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer("labor-identity").audience("labor-backend")
                .subject("7").jwtID(UUID.randomUUID().toString()).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", 11L).claim("permissions", List.of("foundation:files:read"));
        change.accept(builder);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }

    @TestConfiguration
    @MapperScan(basePackageClasses = IsolationMapper.class, annotationClass = Mapper.class)
    static class Fixtures {
        @Bean
        FixtureHandler fixtureHandler() {
            return new FixtureHandler();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    static class FixtureHandler implements TaskHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean fail;
        private volatile CountDownLatch started;
        private volatile CountDownLatch release;

        @Override
        public String type() {
            return "fixture";
        }

        @Override
        public void execute(long taskId, String payload) throws Exception {
            calls.incrementAndGet();
            assertEquals(11L, ExecutionContext.requireTenant());
            assertEquals(7L, ExecutionContext.require().actorId());
            assertTrue(ExecutionContext.require().permissions().isEmpty());
            if (started != null) {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            if (fail) {
                throw new IllegalStateException("fixture");
            }
        }
    }

    @RestController
    static class ProbeController {
        record Input(@NotBlank String name) {
        }

        @GetMapping("/probe")
        Result<ExecutionContext.Identity> identity() {
            return Result.success(ExecutionContext.require());
        }

        @PostMapping("/probe")
        Result<String> input(@Valid @RequestBody Input input) {
            return Result.success(input.name());
        }

        @GetMapping("/probe/permission")
        @PreAuthorize("hasAuthority('fixture:permission')")
        Result<Void> restricted() {
            return Result.success();
        }
    }
}
