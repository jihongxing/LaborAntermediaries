package com.company.labor.modules.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.labor.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import com.nimbusds.jwt.SignedJWT;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 在独立 PostgreSQL 上验证注册、身份脱敏、密码找回、Refresh 轮换与并发验证码消费。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthenticationIT.ProviderTestConfiguration.class)
class AuthenticationIT {
    /** 测试进程独占的 RSA PEM 临时目录。 */
    private static final Path ROOT;
    /** 本地 Access Token 签发私钥。 */
    private static final Path PRIVATE_KEY;
    /** 本地 Access Token 校验公钥。 */
    private static final Path PUBLIC_KEY;
    /** 与测试公钥对应的合成私钥。 */
    private static final KeyPair KEYS;

    static {
        try {
            ROOT = Files.createTempDirectory("labor-auth-it-");
            PRIVATE_KEY = ROOT.resolve("private.pem");
            PUBLIC_KEY = ROOT.resolve("public.pem");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            Files.writeString(PRIVATE_KEY, pem("PRIVATE KEY", KEYS.getPrivate().getEncoded()));
            Files.writeString(PUBLIC_KEY, pem("PUBLIC KEY", KEYS.getPublic().getEncoded()));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * 强制集成测试绑定独立 `_test` 数据库和合成认证密钥。
     * @param registry Spring 动态配置注册表
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test")) {
            throw new IllegalStateException("TEST_DATABASE_URL must name a dedicated PostgreSQL database ending in _test");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        registry.add("foundation.security.public-key", PUBLIC_KEY::toUri);
        registry.add("foundation.security.issuer", () -> "labor-identity");
        registry.add("foundation.authentication.enabled", () -> true);
        registry.add("foundation.authentication.mock-sms-enabled", () -> true);
        registry.add("foundation.authentication.private-key", PRIVATE_KEY::toUri);
        registry.add("foundation.authentication.public-key", PUBLIC_KEY::toUri);
        registry.add("foundation.authentication.phone-hmac-key", () -> "synthetic integration-only phone HMAC key value");
        registry.add("foundation.authentication.refresh-cookie-secure", () -> false);
        registry.add("foundation.crypto.active-key", () -> "integration-test");
        registry.add("foundation.crypto.keys.integration-test", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("foundation.security.allowed-origins[0]", () -> "http://127.0.0.1:3000");
    }

    /** 数据库查询入口，仅用于前后置状态确认。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 验证实际匿名 API、安全过滤器和 Cookie 行为。 */
    @Autowired
    private MockMvc http;
    /** Profile 限定的验证码收件箱。 */
    @Autowired
    private MockSmsMailbox mailbox;
    /** 解析 API envelope，不把框架字段用于测试判断。 */
    @Autowired
    private ObjectMapper mapper;
    /** 认证挑战服务，用于并发消费验证。 */
    @Autowired
    private AuthChallengeService challenges;
    /** 平台授权服务，用于模拟受控离线首次引导。 */
    @Autowired
    private PlatformAuthorizationService platformAuthorizations;
    /** 按批准期限清理技术认证数据。 */
    @Autowired
    private AuthRetentionCleanup retentionCleanup;
    /**
     * 清理前一场景留下的合成认证记录，不重置 PostgreSQL 全局序列。
     */
    @BeforeEach
    void clearAuthenticationRecords() {
        jdbc.execute("TRUNCATE TABLE platform_authorization_event, platform_authorization_bootstrap, "
                + "platform_authorization_change_request, platform_authorization_grant");
        // 平台通知和认领记录保留任务、事项及账号外键，须在账号清理前一同截断。
        jdbc.execute("TRUNCATE TABLE platform_task_recovery_notice, platform_task_claim_request, "
                + "platform_task_conflict, platform_task_claim, "
                + "platform_review_evidence_verification, platform_appeal_review_result, platform_review_task, "
                + "platform_appeal_case, platform_review_correction_item, platform_review_case");
        jdbc.update("DELETE FROM auth_refresh_session");
        jdbc.update("DELETE FROM auth_identity_binding_ticket");
        jdbc.update("DELETE FROM auth_password_reset_ticket");
        jdbc.update("DELETE FROM auth_consent_record");
        jdbc.update("DELETE FROM auth_security_event");
        jdbc.update("DELETE FROM auth_verification_challenge");
        jdbc.update("DELETE FROM auth_credential");
        jdbc.update("DELETE FROM auth_login_identity");
        jdbc.update("DELETE FROM auth_account");
    }

    /** 验证平台岗位必须经受控引导和独立上下文进入，角色权限实时生效且撤权立即拒绝旧令牌。 */
    @Test
    void platformAccessRequiresLiveGrantAndRevocationInvalidatesExistingToken() throws Exception {
        Registered administrator = register("13011130111");
        Registered organizationReviewer = register("13011130112");
        Registered ordinaryMember = register("13011130114");
        long administratorId = accountId(administrator.accessToken());
        long reviewerId = accountId(organizationReviewer.accessToken());
        long ordinaryMemberId = accountId(ordinaryMember.accessToken());
        insertOrganization(ordinaryMemberId, "EMPLOYER", "APPROVED", true);

        http.perform(get("/platform/tasks/recovery-notices"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/platform/tasks/recovery-notices")
                        .header("Authorization", "Bearer " + ordinaryMember.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());

        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + administrator.accessToken()))
                .andExpect(status().isNotFound());
        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + ordinaryMember.accessToken()))
                .andExpect(status().isNotFound());
        http.perform(get("/platform/authorizations/changes/pending")
                        .header("Authorization", "Bearer " + organizationReviewer.accessToken()))
                .andExpect(status().isForbidden());

        long bootstrapGrantId = platformAuthorizations.bootstrap(99001, administratorId,
                Instant.now().plusSeconds(3600), "受控集成测试首次授权");
        assertTrue(bootstrapGrantId > 0);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_authorization_bootstrap", Integer.class));
        assertThrows(BusinessException.class, () -> platformAuthorizations.bootstrap(99002, reviewerId,
                Instant.now().plusSeconds(3600), "重复引导必须拒绝"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_authorization_grant", Integer.class));

        String administratorToken = enterPlatformContext(administrator.accessToken());
        SignedJWT parsedAdministratorToken = SignedJWT.parse(administratorToken);
        assertEquals("PLATFORM", parsedAdministratorToken.getJWTClaimsSet().getStringClaim("context_type"));
        assertEquals("AUTHORIZATION_ADMIN", parsedAdministratorToken.getJWTClaimsSet().getStringClaim("actor_role"));
        assertFalse(parsedAdministratorToken.getJWTClaimsSet().getClaims().containsKey("tenant_id"));

        String reviewerExpiry = Instant.now().plusSeconds(1800).toString();
        MvcResult grantRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"ORGANIZATION_REVIEWER",
                                  "expiresAt":"%s","reason":"集成测试岗位授权"}
                                """.formatted(reviewerId, reviewerExpiry)))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED", data(grantRequest).path("status").asText());

        String reviewerToken = enterPlatformContext(organizationReviewer.accessToken());
        http.perform(get("/platform/tasks").param("taskType", "ORGANIZATION_REVIEW")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        http.perform(get("/platform/tasks").param("taskType", "DEMAND_REVIEW")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        http.perform(post("/platform/tasks/999999999/claim")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header("Idempotency-Key", "missing-task-request"))
                .andExpect(status().isNotFound());
        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
        http.perform(get("/platform/authorizations/changes/pending")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        http.perform(get("/demands/platform/pending")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());

        MvcResult renewal = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"RENEW","roleCode":"ORGANIZATION_REVIEWER",
                                  "expiresAt":"%s","reason":"集成测试岗位续期"}
                                """.formatted(reviewerId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED", data(renewal).path("status").asText());
        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());

        MvcResult replacement = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"REPLACE","roleCode":"DEMAND_REVIEWER",
                                  "expiresAt":"%s","reason":"集成测试岗位职责调整"}
                                """.formatted(reviewerId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED", data(replacement).path("status").asText());
        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isUnauthorized());

        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MvcResult refreshedReviewer = http.perform(post("/auth/token/refresh")
                        .cookie(organizationReviewer.refreshCookie(), csrfCookie(csrf))
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText()))
                .andExpect(status().isOk()).andReturn();
        String demandReviewerToken = data(refreshedReviewer).path("accessToken").asText();
        http.perform(get("/platform/tasks").param("taskType", "ORGANIZATION_REVIEW")
                        .header("Authorization", "Bearer " + demandReviewerToken))
                .andExpect(status().isForbidden());
        http.perform(get("/platform/tasks").param("taskType", "DEMAND_REVIEW")
                        .header("Authorization", "Bearer " + demandReviewerToken))
                .andExpect(status().isOk());
        http.perform(get("/organizations/platform/pending")
                        .header("Authorization", "Bearer " + demandReviewerToken))
                .andExpect(status().isNotFound());
        http.perform(get("/demands/platform/pending")
                        .header("Authorization", "Bearer " + demandReviewerToken))
                .andExpect(status().isNotFound());

        MvcResult revokeRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"REVOKE","roleCode":"DEMAND_REVIEWER",
                                  "reason":"集成测试撤权"}
                                """.formatted(reviewerId)))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED", data(revokeRequest).path("status").asText());

        http.perform(get("/demands/platform/pending")
                        .header("Authorization", "Bearer " + demandReviewerToken))
                .andExpect(status().isUnauthorized());
    }

    /** 验证监察岗位授予必须经两名不同授权管理员确认，且组织成员不能成为授权管理员或监察员。 */
    @Test
    void independentAuditorAuthorizationRequiresTwoDistinctAdministrators() throws Exception {
        Registered firstAdministrator = register("13011130115");
        Registered secondAdministrator = register("13011130116");
        Registered auditor = register("13011130117");
        Registered rejectedAuditor = register("13011130119");
        Registered organizationMember = register("13011130118");
        long firstAdministratorId = accountId(firstAdministrator.accessToken());
        long secondAdministratorId = accountId(secondAdministrator.accessToken());
        long auditorId = accountId(auditor.accessToken());
        long organizationMemberId = accountId(organizationMember.accessToken());
        insertOrganization(organizationMemberId, "EMPLOYER", "APPROVED", true);

        platformAuthorizations.bootstrap(99101, firstAdministratorId,
                Instant.now().plusSeconds(7200), "合成数据授权管理员引导");
        String firstAdministratorToken = enterPlatformContext(firstAdministrator.accessToken());
        MvcResult memberAdministratorRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + firstAdministratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"AUTHORIZATION_ADMIN",
                                  "expiresAt":"%s","reason":"组织成员不可兼任授权管理员"}
                                """.formatted(organizationMemberId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isForbidden()).andReturn();
        assertNotNull(memberAdministratorRequest);
        MvcResult memberAuditorRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + firstAdministratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"INDEPENDENT_AUDITOR",
                                  "expiresAt":"%s","reason":"组织成员不可兼任独立监察员"}
                                """.formatted(organizationMemberId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isForbidden()).andReturn();
        assertNotNull(memberAuditorRequest);

        MvcResult secondAdminGrant = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + firstAdministratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"AUTHORIZATION_ADMIN",
                                  "expiresAt":"%s","reason":"建立第二名合成授权管理员"}
                                """.formatted(secondAdministratorId, Instant.now().plusSeconds(5400))))
                .andExpect(status().isOk()).andReturn();
        assertEquals("APPROVED", data(secondAdminGrant).path("status").asText());
        String secondAdministratorToken = enterPlatformContext(secondAdministrator.accessToken());

        MvcResult auditorRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + firstAdministratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"INDEPENDENT_AUDITOR",
                                  "expiresAt":"%s","reason":"监察岗位任命须双人确认"}
                                """.formatted(auditorId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andReturn();
        long requestId = data(auditorRequest).path("id").asLong();
        assertEquals("PENDING", data(auditorRequest).path("status").asText());
        http.perform(post("/platform/authorizations/changes/{requestId}/approve", requestId)
                        .header("Authorization", "Bearer " + firstAdministratorToken))
                .andExpect(status().isForbidden());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM platform_authorization_change_request WHERE id = ?", String.class, requestId));

        http.perform(post("/platform/authorizations/changes/{requestId}/approve", requestId)
                        .header("Authorization", "Bearer " + secondAdministratorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertEquals(secondAdministratorId, jdbc.queryForObject(
                "SELECT approved_by FROM platform_authorization_change_request WHERE id = ?", Long.class, requestId));
        String auditorToken = enterPlatformContext(auditor.accessToken());
        SignedJWT auditorJwt = SignedJWT.parse(auditorToken);
        assertEquals("INDEPENDENT_AUDITOR", auditorJwt.getJWTClaimsSet().getStringClaim("actor_role"));
        assertTrue(auditorJwt.getJWTClaimsSet().getStringListClaim("permissions")
                .contains("platform:audit:read"));
        http.perform(get("/platform/authorizations/changes/pending")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden());

        MvcResult rejectedRequest = http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + firstAdministratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"INDEPENDENT_AUDITOR",
                                  "expiresAt":"%s","reason":"测试拒绝流程的监察员候选"}
                                """.formatted(accountId(rejectedAuditor.accessToken()), Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andReturn();
        long rejectedRequestId = data(rejectedRequest).path("id").asLong();
        http.perform(post("/platform/authorizations/changes/{requestId}/reject", rejectedRequestId)
                        .header("Authorization", "Bearer " + secondAdministratorToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"测试双人拒绝记录\"}"))
                .andExpect(status().isOk());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM platform_authorization_change_request WHERE id = ?", String.class,
                rejectedRequestId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_authorization_grant WHERE account_id = ?",
                Integer.class, accountId(rejectedAuditor.accessToken())));
    }

    /** 验证普通登录令牌即使静态包含平台权限，也不能冒充平台上下文读取审核队列。 */
    @Test
    void staticPlatformPermissionInPersonalTokenCannotAuthorizeReviewQueue() throws Exception {
        Registered registered = register("13011130113");
        long accountId = accountId(registered.accessToken());
        String forged = signedToken(accountId, null, "PERSONAL", 1,
                java.util.List.of("platform:organizations:review"), null);

        http.perform(get("/organizations/platform/pending").header("Authorization", "Bearer " + forged))
                .andExpect(status().isNotFound());
    }

    /** 经真实 HTTP API 走通组织提交、审核任务认领、版本详情、补正决定及申请方查看。 */
    @Test
    void organizationReviewApiCompletesClaimedCorrectionWorkflow() throws Exception {
        Registered owner = register("13011130121");
        Registered authorizationAdministrator = register("13011130122");
        Registered reviewer = register("13011130123");
        long administratorId = accountId(authorizationAdministrator.accessToken());
        long reviewerId = accountId(reviewer.accessToken());

        platformAuthorizations.bootstrap(99121, administratorId, Instant.now().plusSeconds(7200),
                "合成 API 验收首次授权");
        String administratorToken = enterPlatformContext(authorizationAdministrator.accessToken());
        http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"ORGANIZATION_REVIEWER",
                                  "expiresAt":"%s","reason":"组织审核 API 验收岗位授权"}
                                """.formatted(reviewerId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        String registrationId = "synthetic-it-review-" + java.util.UUID.randomUUID();
        MvcResult organization = http.perform(post("/organizations")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType("application/json")
                        .content("""
                                {"organizationRole":"EMPLOYER","legalName":"组织审核 API 合成组织",
                                  "registrationId":"%s","authorizationReference":"合成授权引用"}
                                """.formatted(registrationId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT")).andReturn();
        long organizationId = data(organization).path("id").asLong();
        http.perform(post("/organizations/{organizationId}/submit", organizationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        String reviewerToken = enterPlatformContext(reviewer.accessToken());
        MvcResult queue = http.perform(get("/platform/tasks").param("taskType", "ORGANIZATION_REVIEW")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andReturn();
        String taskId = data(queue).get(0).path("taskId").asText();
        http.perform(get("/platform/tasks/{taskId}/organization", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        http.perform(post("/platform/tasks/{taskId}/claim", taskId)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header("Idempotency-Key", "api-org-review-" + taskId))
                .andExpect(status().isOk());

        http.perform(get("/platform/tasks/{taskId}/organization", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").value(Long.toString(organizationId)))
                .andExpect(jsonPath("$.data.applicationVersion").value(1));
        http.perform(get("/platform/tasks/{taskId}/evidence", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        http.perform(get("/platform/tasks/{taskId}/evidence/verifications", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        http.perform(get("/platform/tasks/{taskId}/organization", taskId)
                        .header("Authorization", "Bearer " + administratorToken))
                .andExpect(status().isForbidden());

        http.perform(post("/platform/tasks/{taskId}/organization/decision", taskId)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType("application/json")
                        .content("""
                                {"decision":"NEEDS_CORRECTION","reason":"请补充组织授权材料",
                                  "correctionItems":[{"itemCode":"AUTHORITY","title":"经办授权链",
                                    "description":"当前材料无法确认签署人的组织授权范围",
                                    "applicantAction":"补充有效授权文件并说明授权范围"}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"));

        String organizationDecisionId = jdbc.queryForObject("""
                SELECT id FROM business_review_decision
                WHERE tenant_id = ? AND resource_type = 'ORGANIZATION' AND resource_id = ?
                """, String.class, organizationId, organizationId);
        http.perform(get("/organizations/{organizationId}/review-history", organizationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStatus").value("NEEDS_CORRECTION"))
                .andExpect(jsonPath("$.data.versions[0].decisionId").value(organizationDecisionId))
                .andExpect(jsonPath("$.data.unresolvedItems[0]")
                        .value("经办授权链：补充有效授权文件并说明授权范围"))
                .andExpect(jsonPath("$.data.versions[0].correctionItems[0].itemCode").value("AUTHORITY"));
        http.perform(get("/organizations/{organizationId}/review-history", organizationId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        http.perform(post("/review-decisions/{decisionId}/appeals", organizationDecisionId)
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"无关平台人员不得代申请方发起异议\"}"))
                .andExpect(status().isForbidden());
        http.perform(post("/review-decisions/{decisionId}/appeals", organizationDecisionId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType("application/json")
                        .content("{\"reason\":\"对补正结论申请独立复核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        http.perform(get("/organizations/{organizationId}/review-history", organizationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appeals[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.appeals[0].outcome").doesNotExist())
                .andExpect(jsonPath("$.data.appeals[0].reason").doesNotExist());
    }

    /** 验证需求审核岗位通过专用路由读取当前版本并提交逐项补正，申请方可读取未结事项。 */
    @Test
    void demandReviewApiCompletesClaimedCorrectionWorkflow() throws Exception {
        Registered owner = register("13011130131");
        Registered authorizationAdministrator = register("13011130132");
        Registered reviewer = register("13011130133");
        long ownerId = accountId(owner.accessToken());
        long administratorId = accountId(authorizationAdministrator.accessToken());
        long reviewerId = accountId(reviewer.accessToken());
        platformAuthorizations.bootstrap(99131, administratorId, Instant.now().plusSeconds(7200),
                "合成需求审核 API 验收首次授权");
        String administratorToken = enterPlatformContext(authorizationAdministrator.accessToken());
        http.perform(post("/platform/authorizations/changes")
                        .header("Authorization", "Bearer " + administratorToken)
                        .contentType("application/json")
                        .content("""
                                {"targetAccountId":"%d","action":"GRANT","roleCode":"DEMAND_REVIEWER",
                                  "expiresAt":"%s","reason":"需求审核 API 验收岗位授权"}
                                """.formatted(reviewerId, Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        long organizationId = insertOrganization(ownerId, "EMPLOYER", "APPROVED", true);
        String organizationToken = enterOrganizationContext(owner.accessToken(), organizationId);
        Instant startsAt = Instant.now().plusSeconds(86_400);
        MvcResult demand = http.perform(post("/demands/organizations/{organizationId}", organizationId)
                        .header("Authorization", "Bearer " + organizationToken)
                        .header("Idempotency-Key", "api-demand-review-create-" + organizationId)
                        .contentType("application/json")
                        .content("""
                                {"relatedPartyRelation":"用工方直接签约并付款","serviceScope":"现场服务支持",
                                  "location":"合成测试地点","startsAt":"%s","endsAt":"%s",
                                  "title":"需求审核 API 合成需求","quantity":3,"quantityUnit":"人",
                                  "target":"具备现场服务能力","pricingMode":"按人日","measurementBasis":"签到和工时",
                                  "settlementCycle":"月结","bidDeadline":"%s","confirmationDeadline":"%s",
                                  "selectionRule":"按响应条件和配额规则评审"}
                                """.formatted(startsAt, startsAt.plusSeconds(86_400),
                                startsAt.minusSeconds(3600), startsAt.plusSeconds(172_800))))
                .andExpect(status().isOk()).andReturn();
        long demandId = data(demand).path("id").asLong();
        http.perform(post("/demands/{demandId}/submit", demandId)
                        .header("Authorization", "Bearer " + organizationToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        String reviewerToken = enterPlatformContext(reviewer.accessToken());
        MvcResult queue = http.perform(get("/platform/tasks").param("taskType", "DEMAND_REVIEW")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk()).andReturn();
        String taskId = data(queue).get(0).path("taskId").asText();
        http.perform(get("/platform/tasks/{taskId}/demand", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
        http.perform(post("/platform/tasks/{taskId}/claim", taskId)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .header("Idempotency-Key", "api-demand-review-" + taskId))
                .andExpect(status().isOk());
        http.perform(get("/platform/tasks/{taskId}/demand", taskId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.demandId").value(Long.toString(demandId)))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.title").value("需求审核 API 合成需求"))
                .andExpect(jsonPath("$.data.organizationId").doesNotExist());
        http.perform(post("/platform/tasks/{taskId}/demand/decision", taskId)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType("application/json")
                        .content("""
                                {"decision":"NEEDS_CORRECTION","reason":"请补充需求响应边界",
                                  "correctionItems":[{"itemCode":"SERVICE_SCOPE","title":"服务边界",
                                    "description":"当前需求缺少可核对的服务边界","applicantAction":"补充人员到岗和验收要求"}]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"));
        String demandDecisionId = jdbc.queryForObject("""
                SELECT id FROM business_review_decision
                WHERE tenant_id = ? AND resource_type = 'DEMAND' AND resource_id = ?
                """, String.class, organizationId, demandId);
        http.perform(get("/demands/{demandId}/review-history", demandId)
                        .header("Authorization", "Bearer " + organizationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions[0].decisionId").value(demandDecisionId))
                .andExpect(jsonPath("$.data.unresolvedItems[0]")
                        .value("服务边界：补充人员到岗和验收要求"));
        http.perform(post("/review-decisions/{decisionId}/appeals", demandDecisionId)
                        .header("Authorization", "Bearer " + organizationToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"对需求补正结论申请独立复核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        http.perform(get("/demands/{demandId}/review-history", demandId)
                        .header("Authorization", "Bearer " + organizationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appeals[0].status").value("PENDING"));
    }

    /** 验证注册只持久化手机号密文和摘要，并签发能通过本地 Resource Server 的 JWT。 */
    @Test
    void registersVerifiedPhoneAndIssuesHttpOnlySession() throws Exception {
        String phone = "13800138000";
        StartedChallenge started = start("register", phone);
        String code = mailbox.get(started.id()).code();
        MvcResult response = http.perform(post("/auth/register/verify")
                        .contentType("application/json")
                        .content("""
                                {"phone":"%s","challengeId":"%s","code":"%s","password":"Correct-horse battery! 42",
                                  "displayName":"测试账号","termsVersion":"terms-v1","privacyVersion":"privacy-v1"}
                                """.formatted(phone, started.id(), code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        MockHttpServletResponse raw = response.getResponse();
        String refreshHeader = raw.getHeader("Set-Cookie");
        assertTrue(refreshHeader.contains("HttpOnly"));
        assertTrue(refreshHeader.contains("SameSite=Strict"));
        String subject = jdbc.queryForObject("SELECT subject_ciphertext FROM auth_login_identity WHERE provider = 'PHONE'",
                String.class);
        assertNotNull(subject);
        assertFalse(subject.contains(phone));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM auth_consent_record", Integer.class));
        String accessToken = data(response).path("accessToken").asText();
        http.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    /** 验证密码找回重置密码后，所有旧 Refresh Session 都不能再刷新。 */
    @Test
    void passwordResetRevokesExistingRefreshSessions() throws Exception {
        Registered registered = register("13900139000");
        StartedChallenge reset = start("password-reset", registered.phone());
        String code = mailbox.get(reset.id()).code();
        MvcResult ticketResponse = http.perform(post("/auth/password-reset/verify")
                        .contentType("application/json").content("""
                                {"challengeId":"%s","code":"%s"}
                                """.formatted(reset.id(), code)))
                .andExpect(status().isOk()).andReturn();
        JsonNode ticket = data(ticketResponse);
        http.perform(post("/auth/password-reset/complete").contentType("application/json").content("""
                        {"ticketId":"%s","resetToken":"%s","newPassword":"New-correct horse 2026!"}
                        """.formatted(ticket.path("ticketId").asText(), ticket.path("resetToken").asText())))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_session WHERE revoked_at IS NOT NULL", Integer.class));

        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        http.perform(post("/auth/token/refresh").cookie(registered.refreshCookie(), csrfCookie(csrf))
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText()))
                .andExpect(status().isUnauthorized());
    }

    /** 验证 Refresh Token 轮换后重放旧令牌会撤销整个会话族。 */
    @Test
    void refreshReuseRevokesTheWholeSessionFamily() throws Exception {
        Registered registered = register("13700137000");
        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        String csrfValue = data(csrf).path("token").asText();
        http.perform(post("/auth/token/refresh").cookie(registered.refreshCookie(), csrfCookie(csrf))
                        .header("Origin", "http://127.0.0.1:3000").header("X-CSRF-Token", csrfValue))
                .andExpect(status().isOk());

        http.perform(post("/auth/token/refresh").cookie(registered.refreshCookie(), csrfCookie(csrf))
                        .header("Origin", "http://127.0.0.1:3000").header("X-CSRF-Token", csrfValue))
                .andExpect(status().isUnauthorized());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_session WHERE revoked_at IS NOT NULL", Integer.class));
    }

    /** 验证验证码尝试计数达到五次后持久化锁定，错误响应不回显提交值。 */
    @Test
    void challengeLocksAfterFiveFailedCodes() throws Exception {
        StartedChallenge started = start("register", "13600136000");
        for (int index = 0; index < 5; index++) {
            http.perform(post("/auth/register/verify").contentType("application/json").content("""
                            {"phone":"13600136000","challengeId":"%s","code":"000000","password":"Correct-horse battery! 42",
                              "termsVersion":"terms-v1","privacyVersion":"privacy-v1"}
                            """.formatted(started.id())))
                    .andExpect(status().isBadRequest())
                    .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("000000")));
        }
        assertEquals("LOCKED", jdbc.queryForObject("SELECT state FROM auth_verification_challenge WHERE id = ?",
                String.class, started.id()));
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM auth_verification_challenge WHERE id = ?",
                Integer.class, started.id()));
    }

    /** 验证同一挑战的并发正确验证码至多成功消费一次。 */
    @Test
    void challengeCanBeConsumedOnlyOnceAcrossConcurrentRequests() throws Exception {
        String phone = "13500135000";
        AuthChallengeService.ChallengeStart challenge = challenges.start(phone, "REGISTER", "127.0.0.1");
        String code = mailbox.get(challenge.challengeId()).code();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 2; index++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        challenges.consume(challenge.challengeId(), "REGISTER", code);
                        successes.incrementAndGet();
                    } catch (Exception expected) {
                        // 另一线程已消费，数据库锁和终态应拒绝重复验证。
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, successes.get());
    }

    /** 验证短信登录和备用密码登录只为已验证手机号签发账号会话。 */
    @Test
    void smsAndPasswordLoginAuthenticateTheSameAccount() throws Exception {
        Registered registered = register("13300133000");
        String accountId = SignedJWT.parse(registered.accessToken()).getJWTClaimsSet().getSubject();
        MvcResult passwordLogin = http.perform(post("/auth/login/password").contentType("application/json")
                        .content("{\"phone\":\"13300133000\",\"password\":\"Correct-horse battery! 42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.accountId").value(accountId))
                .andReturn();
        assertTrue(data(passwordLogin).path("accessToken").isTextual());

        StartedChallenge loginChallenge = start("login", registered.phone());
        MvcResult smsLogin = http.perform(post("/auth/login/sms/verify").contentType("application/json")
                        .content("{\"challengeId\":\"" + loginChallenge.id() + "\",\"code\":\""
                                + mailbox.get(loginChallenge.id()).code() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.status").value("ACTIVE"))
                .andReturn();
        assertTrue(data(smsLogin).path("accessToken").isTextual());
        http.perform(post("/auth/login/password").contentType("application/json")
                        .content("{\"phone\":\"13300133000\",\"password\":\"incorrect password 2026\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** 验证个人上下文只允许发起人保存和提交自己创建的组织准入申请。 */
    @Test
    void personalAccountCanSubmitOnlyItsOwnOrganizationApplication() throws Exception {
        Registered registered = register("13200132000");
        String registrationId = "synthetic-it-org-" + java.util.UUID.randomUUID();
        String body = """
                {"organizationRole":"EMPLOYER","legalName":"合成用工组织","registrationId":"%s",
                  "authorizationReference":"synthetic-auth-ref"}
                """.formatted(registrationId);
        MvcResult created = http.perform(post("/organizations").contentType("application/json")
                        .header("Authorization", "Bearer " + registered.accessToken()).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String organizationId = data(created).path("id").asText();
        http.perform(post("/organizations/" + organizationId + "/submit")
                        .header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        http.perform(post("/organizations/999999999/submit")
                        .header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isForbidden());
    }

    /** 验证组织选择仅接受已审核组织和有效成员，切换后旧令牌及撤权后的新令牌均立即失效。 */
    @Test
    void organizationContextRequiresApprovalAndActiveMembershipAndTracksRevocation() throws Exception {
        Registered registered = register("13400134000");
        long accountId = Long.parseLong(SignedJWT.parse(registered.accessToken()).getJWTClaimsSet().getSubject());
        long approvedOrg = insertOrganization(accountId, "EMPLOYER", "APPROVED", true);
        long pendingOrg = insertOrganization(accountId, "LABOR_SUPPLIER", "PENDING_REVIEW", true);
        long unrelatedOrg = insertOrganization(accountId, "EMPLOYER", "APPROVED", false);

        MvcResult orgList = http.perform(get("/auth/organizations")
                        .header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].organizationStatus").exists())
                .andReturn();
        assertNotNull(data(orgList));

        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        http.perform(post("/auth/context").contentType("application/json")
                        .header("Authorization", "Bearer " + registered.accessToken())
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText())
                        .cookie(csrfCookie(csrf))
                        .content("{\"organizationId\":\"" + pendingOrg + "\"}"))
                .andExpect(status().isForbidden());
        http.perform(post("/auth/context").contentType("application/json")
                        .header("Authorization", "Bearer " + registered.accessToken())
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText())
                        .cookie(csrfCookie(csrf))
                        .content("{\"organizationId\":\"" + unrelatedOrg + "\"}"))
                .andExpect(status().isForbidden());

        MvcResult switched = http.perform(post("/auth/context").contentType("application/json")
                        .header("Authorization", "Bearer " + registered.accessToken())
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText())
                        .cookie(csrfCookie(csrf))
                        .content("{\"organizationId\":\"" + approvedOrg + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String organizationToken = data(switched).path("accessToken").asText();
        SignedJWT parsed = SignedJWT.parse(organizationToken);
        assertEquals(approvedOrg, parsed.getJWTClaimsSet().getLongClaim("tenant_id"));
        assertTrue(parsed.getJWTClaimsSet().getStringListClaim("permissions").contains("organization:demands:write"));
        http.perform(get("/auth/me").header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isUnauthorized());
        http.perform(get("/organizations/" + unrelatedOrg).header("Authorization", "Bearer " + organizationToken))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE business_membership SET status = 'REVOKED' WHERE organization_id = ? AND actor_id = ?",
                approvedOrg, accountId);
        http.perform(get("/auth/me").header("Authorization", "Bearer " + organizationToken))
                .andExpect(status().isUnauthorized());
    }

    /** 验证待补正组织管理员可取回受控材料引用、修订资料并重新提交平台审核。 */
    @Test
    void organizationApplicantCanCorrectAndResubmitApplication() throws Exception {
        Registered registered = register("13200132000");
        long accountId = Long.parseLong(SignedJWT.parse(registered.accessToken()).getJWTClaimsSet().getSubject());
        long organizationId = insertOrganization(accountId, "LABOR_SUPPLIER", "NEEDS_CORRECTION", true);

        http.perform(get("/organizations/" + organizationId)
                        .header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationReference").value("synthetic-authorization"))
                .andExpect(jsonPath("$.data.qualificationReference").value("synthetic-qualification"))
                .andExpect(jsonPath("$.data.reviewReason").value("请补充供应商服务范围"));

        http.perform(put("/organizations/" + organizationId).contentType("application/json")
                        .header("Authorization", "Bearer " + registered.accessToken())
                        .content("""
                                {"expectedVersion":1,"legalName":"修订后的劳务供应商",
                                  "registrationId":"synthetic-registration-%d-corrected",
                                  "authorizationReference":"updated-authorization",
                                  "qualificationReference":"updated-qualification"}
                                """.formatted(organizationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NEEDS_CORRECTION"))
                .andExpect(jsonPath("$.data.applicationVersion").value(2));

        http.perform(post("/organizations/" + organizationId + "/submit")
                        .header("Authorization", "Bearer " + registered.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }

    /** 验证组织普通成员可读准入状态，但不能读取组织管理员的受控材料引用。 */
    @Test
    void organizationMaterialsAreVisibleOnlyToAdministratorAndPlatformReviewer() throws Exception {
        Registered administrator = register("13910139100");
        long administratorId = Long.parseLong(
                SignedJWT.parse(administrator.accessToken()).getJWTClaimsSet().getSubject());
        long organizationId = insertOrganization(administratorId, "LABOR_SUPPLIER", "APPROVED", true);
        Registered member = register("13810138100");
        long memberId = Long.parseLong(SignedJWT.parse(member.accessToken()).getJWTClaimsSet().getSubject());
        jdbc.update("""
                INSERT INTO business_membership(organization_id, tenant_id, actor_id, member_role, status, created_by)
                VALUES (?, ?, ?, 'SUPPLIER_BIDDER', 'ACTIVE', ?)
                """, organizationId, organizationId, memberId, administratorId);

        MvcResult response = http.perform(get("/organizations/" + organizationId)
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isOk()).andReturn();
        assertTrue(data(response).path("authorizationReference").isNull());
        assertTrue(data(response).path("qualificationReference").isNull());
    }

    /** 验证外部身份只可由已登录账号用短期票据显式绑定一次，并阻止账号间自动认领。 */
    @Test
    void externalIdentityBindingRequiresExplicitAccountSessionAndRejectsConflicts() throws Exception {
        Registered firstAccount = register("13100131000");
        MvcResult ticketResponse = http.perform(post("/auth/identities/WECHAT_MINIPROGRAM/binding-ticket")
                        .header("Authorization", "Bearer " + firstAccount.accessToken()))
                .andExpect(status().isOk()).andReturn();
        JsonNode ticket = data(ticketResponse);
        String body = """
                {"ticketId":"%s","ticket":"%s","authorizationCode":"union-subject-1"}
                """.formatted(ticket.path("ticketId").asText(), ticket.path("ticket").asText());
        http.perform(post("/auth/identities/WECHAT_MINIPROGRAM/bind")
                        .header("Authorization", "Bearer " + firstAccount.accessToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("WECHAT_MINIPROGRAM"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_login_identity "
                + "WHERE provider = 'WECHAT_MINIPROGRAM'", Integer.class));
        http.perform(post("/auth/identities/WECHAT_MINIPROGRAM/bind")
                        .header("Authorization", "Bearer " + firstAccount.accessToken())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        Registered secondAccount = register("13000130000");
        JsonNode secondTicket = data(http.perform(post("/auth/identities/WECHAT_MINIPROGRAM/binding-ticket")
                        .header("Authorization", "Bearer " + secondAccount.accessToken()))
                .andExpect(status().isOk()).andReturn());
        String conflictBody = """
                {"ticketId":"%s","ticket":"%s","authorizationCode":"union-subject-1"}
                """.formatted(secondTicket.path("ticketId").asText(), secondTicket.path("ticket").asText());
        http.perform(post("/auth/identities/WECHAT_MINIPROGRAM/bind")
                        .header("Authorization", "Bearer " + secondAccount.accessToken())
                        .contentType("application/json").content(conflictBody))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_login_identity "
                + "WHERE provider = 'WECHAT_MINIPROGRAM'", Integer.class));
    }

    /** 验证清理仅删除超过保留期的终态挑战、重置票据、会话和安全事件。 */
    @Test
    void retentionCleanupRemovesOnlyExpiredTerminalAuthenticationData() throws Exception {
        Registered registered = register("13500135000");
        long accountId = Long.parseLong(SignedJWT.parse(registered.accessToken()).getJWTClaimsSet().getSubject());
        StartedChallenge terminal = start("login", "13600136000");
        StartedChallenge active = start("login", "13700137000");
        StartedChallenge expired = start("login", "13820138200");
        Instant old = Instant.now().minusSeconds(3L * 365 * 24 * 60 * 60);
        Instant future = Instant.now().plusSeconds(60 * 60);
        jdbc.update("UPDATE auth_verification_challenge SET state = 'CONSUMED', expires_at = ?, consumed_at = ?, "
                        + "created_at = ? WHERE id = ?",
                Timestamp.from(old), Timestamp.from(old), Timestamp.from(old), terminal.id());
        jdbc.update("INSERT INTO auth_password_reset_ticket "
                        + "(id, account_id, challenge_id, token_hash, expires_at, consumed_at) "
                        + "VALUES ('old-ticket', ?, ?, 'old-token-hash', ?, ?)",
                accountId, terminal.id(), Timestamp.from(old), Timestamp.from(old));
        jdbc.update("UPDATE auth_verification_challenge SET created_at = ? WHERE id = ?",
                Timestamp.from(old), active.id());
        jdbc.update("UPDATE auth_verification_challenge SET expires_at = ?, created_at = ? WHERE id = ?",
                Timestamp.from(old), Timestamp.from(old), expired.id());
        jdbc.update("UPDATE auth_refresh_session SET revoked_at = ?, expires_at = ? WHERE account_id = ?",
                Timestamp.from(old), Timestamp.from(future), accountId);
        jdbc.update("INSERT INTO auth_refresh_session "
                        + "(id, account_id, family_id, token_hash, client_type, expires_at) "
                        + "VALUES ('active-session', ?, 'active-family', 'active-token-hash', 'WEB', ?)",
                accountId, Timestamp.from(future));
        jdbc.update("INSERT INTO auth_security_event(account_id, event_type, outcome, created_at) "
                        + "VALUES (?, 'OLD_TEST', 'SUCCESS', ?), (?, 'RECENT_TEST', 'SUCCESS', CURRENT_TIMESTAMP)",
                accountId, Timestamp.from(old), accountId);

        retentionCleanup.cleanExpiredRecords();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_verification_challenge WHERE id = ?",
                Integer.class, terminal.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_password_reset_ticket WHERE id = 'old-ticket'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_verification_challenge WHERE id = ?",
                Integer.class, active.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_verification_challenge WHERE id = ?",
                Integer.class, expired.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_session WHERE account_id = ?",
                Integer.class, accountId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_event WHERE event_type = 'OLD_TEST'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_security_event WHERE event_type = 'RECENT_TEST'",
                Integer.class));
    }

    /** 验证同一来源 IP 的并发短信发送仍受 PostgreSQL 窗口计数约束。 */
    @Test
    void concurrentSmsStartsCannotExceedPerIpHourlyLimit() throws Exception {
        int requests = 24;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests);
        try {
            var results = java.util.stream.IntStream.range(0, requests).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent request barrier timed out");
                }
                return http.perform(post("/auth/register/start").contentType("application/json")
                        .content("{\"phone\":\"139" + String.format("%08d", index) + "\"}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long accepted = 0;
            long limited = 0;
            for (var result : results) {
                int status = result.get(10, TimeUnit.SECONDS);
                if (status == 200) accepted++;
                if (status == 429) limited++;
            }
            assertEquals(20, accepted);
            assertEquals(4, limited);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 发起指定用途短信并取得 profile 限定邮箱中的合成验证码。
     * @param purpose API 路径用途
     * @param phone 合成手机号
     * @return 挑战编号和手机号
     */
    private StartedChallenge start(String purpose, String phone) throws Exception {
        String endpoint = switch (purpose) {
            case "register" -> "/auth/register/start";
            case "password-reset" -> "/auth/password-reset/start";
            default -> "/auth/login/sms/start";
        };
        MvcResult result = http.perform(post(endpoint).contentType("application/json")
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return new StartedChallenge(data(result).path("challengeId").asText(), phone);
    }

    /** 从已签名测试访问令牌读取账号主体编号。 */
    private long accountId(String accessToken) throws Exception {
        return Long.parseLong(SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject());
    }

    /** 使用当前个人访问令牌及双提交 CSRF 进入已授权的平台执行上下文。 */
    private String enterPlatformContext(String accessToken) throws Exception {
        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MvcResult switched = http.perform(post("/auth/platform-context")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText())
                        .cookie(csrfCookie(csrf)))
                .andExpect(status().isOk()).andReturn();
        return data(switched).path("accessToken").asText();
    }

    /** 使用当前个人访问令牌及双提交 CSRF 进入指定已准入组织上下文。 */
    private String enterOrganizationContext(String accessToken, long organizationId) throws Exception {
        MvcResult csrf = http.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MvcResult switched = http.perform(post("/auth/context")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-CSRF-Token", data(csrf).path("token").asText())
                        .cookie(csrfCookie(csrf))
                        .contentType("application/json")
                        .content("{\"organizationId\":" + organizationId + "}"))
                .andExpect(status().isOk()).andReturn();
        return data(switched).path("accessToken").asText();
    }

    /** 使用测试私钥签发任意本地 JWT，用于验证客户端声明本身不能建立平台授权。 */
    private String signedToken(long accountId, String sessionId, String contextType, int contextVersion,
                               java.util.List<String> permissions, String actorRole) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet.Builder claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer("labor-auth").audience(java.util.List.of("labor-backend"))
                .subject(Long.toString(accountId)).issueTime(java.util.Date.from(Instant.now()))
                .notBeforeTime(java.util.Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(600)))
                .jwtID(java.util.UUID.randomUUID().toString())
                .claim("context_type", contextType).claim("context_version", contextVersion)
                .claim("permissions", permissions);
        if (sessionId != null) {
            claims.claim("session_id", sessionId);
        }
        if (actorRole != null) {
            claims.claim("actor_role", actorRole);
        }
        com.nimbusds.jwt.SignedJWT token = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims.build());
        token.sign(new com.nimbusds.jose.crypto.RSASSASigner((java.security.interfaces.RSAPrivateKey) KEYS.getPrivate()));
        return token.serialize();
    }

    /**
     * 注册一个隔离测试账号并提取其浏览器 Refresh Cookie。
     * @param phone 合成且唯一手机号
     * @return 测试账号、刷新 Cookie 和手机号
     */
    private Registered register(String phone) throws Exception {
        StartedChallenge started = start("register", phone);
        String code = mailbox.get(started.id()).code();
        MvcResult result = http.perform(post("/auth/register/verify").contentType("application/json").content("""
                        {"phone":"%s","challengeId":"%s","code":"%s","password":"Correct-horse battery! 42",
                          "termsVersion":"terms-v1","privacyVersion":"privacy-v1"}
                        """.formatted(phone, started.id(), code)))
                .andExpect(status().isOk()).andReturn();
        return new Registered(phone, cookie(result, "labor_refresh"), data(result).path("accessToken").asText());
    }

    /**
     * 建立供组织上下文测试使用的合成组织，并可选登记账号为活动管理员。
     * @param accountId 合成账号编号
     * @param role 组织业务角色
     * @param status 组织审核状态
     * @param activeMember 是否建立活动管理员关系
     * @return 新组织编号
     */
    private long insertOrganization(long accountId, String role, String status, boolean activeMember) {
        long organizationId = jdbc.queryForObject("SELECT nextval('foundation_id_seq')", Long.class);
        jdbc.update("""
                INSERT INTO business_organization(id, tenant_id, organization_role, legal_name, registration_id,
                    authorization_reference, qualification_reference, status, created_by, review_reason)
                VALUES (?, ?, ?, ?, ?, 'synthetic-authorization', ?, ?, ?, '请补充供应商服务范围')
                """, organizationId, organizationId, role, "合成组织" + organizationId,
                "synthetic-registration-" + organizationId,
                role.equals("LABOR_SUPPLIER") ? "synthetic-qualification" : null, status, accountId);
        if (activeMember) {
            jdbc.update("""
                    INSERT INTO business_membership(organization_id, tenant_id, actor_id, member_role, status, created_by)
                    VALUES (?, ?, ?, 'ORGANIZATION_ADMIN', 'ACTIVE', ?)
                    """, organizationId, organizationId, accountId, accountId);
        }
        return organizationId;
    }

    /** 测试专用授权码交换器，只在 PostgreSQL 集成测试上下文中解析合成身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {
        /**
         * 提供测试虚构的 WECHAT_MINIPROGRAM SPI 实现，不进入 main 或生产配置。
         * @return 根据预设授权码返回固定合成主体的测试适配器
         */
        @Bean
        IdentityProvider testIdentityProvider() {
            return new IdentityProvider() {
                /**
                 * 声明测试使用的合成提供商键。
                 * @return 测试提供商标识
                 */
                @Override
                public String providerKey() {
                    return "WECHAT_MINIPROGRAM";
                }

                /**
                 * 仅把固定测试授权码换成合成主体，模拟提供商服务端适配器契约。
                 * @param authorizationCode 集成测试硬编码的假授权码
                 * @param redirectUri 测试未使用的回调地址
                 * @return 固定合成主体，不含真实用户数据
                 */
                @Override
                public VerifiedIdentity exchangeAuthorizationCode(String authorizationCode, String redirectUri) {
                    if (!"union-subject-1".equals(authorizationCode)) {
                        throw new IdentityProviderException("测试授权码无效");
                    }
                    return new VerifiedIdentity(providerKey(), "synthetic-openid-1");
                }
            };
        }
    }

    /**
     * 从 MockMvc 响应中解析成功 envelope 的 data 节点。
     * @param result API 请求结果
     * @return data JSON 对象
     */
    private JsonNode data(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    /**
     * 读取 CSRF 响应的双提交 Cookie。
     * @param result CSRF 接口请求结果
     * @return CSRF Cookie
     */
    private Cookie csrfCookie(MvcResult result) {
        return cookie(result, "labor_csrf");
    }

    /**
     * 从真实 Set-Cookie 响应头读取 Cookie 值，避免依赖 MockServlet 对 ResponseCookie 的解析行为。
     * @param result HTTP 响应
     * @param name Cookie 名称
     * @return 用于后续请求的 Cookie
     */
    private Cookie cookie(MvcResult result, String name) {
        String prefix = name + "=";
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing Set-Cookie header for " + name));
        String value = header.substring(0, header.indexOf(';') >= 0 ? header.indexOf(';') : header.length());
        return new Cookie(name, value.substring(prefix.length()));
    }

    /**
     * 将二进制合成 RSA 数据编码为 PKCS#8 或 X.509 PEM 内容。
     * @param label PEM 边界类型
     * @param encoded ASN.1 DER 密钥数据
     * @return PEM 文本
     */
    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----\n";
    }

    /** 已发起挑战的测试标识。 */
    private record StartedChallenge(String id, String phone) {
        /**
         * @param id 服务端挑战编号
         * @param phone 合成测试手机号
         */
    }

    /** 已注册的测试账号会话快照。 */
    private record Registered(String phone, Cookie refreshCookie, String accessToken) {
        /**
         * @param phone 合成手机号
         * @param refreshCookie 浏览器 Refresh Cookie
         * @param accessToken 当前内存访问令牌
         */
    }
}
