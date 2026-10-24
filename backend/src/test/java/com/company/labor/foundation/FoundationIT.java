package com.company.labor.foundation;

import com.company.labor.common.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
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
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FoundationIT.Fixtures.class)
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
                TRUNCATE foundation_idempotency, foundation_task, foundation_file,
                    foundation_audit, foundation_token_revocation, isolation_fixture, unregistered_fixture
                """);
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
        http.perform(get("/probe/permission").header("Authorization", "Bearer " + token(builder -> { })))
                .andExpect(status().isForbidden());
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
