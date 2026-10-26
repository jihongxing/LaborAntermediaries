package com.company.labor.foundation;

import com.company.labor.modules.demand.DemandDtos.UpsertRequest;
import com.company.labor.modules.demand.DemandDtos;
import com.company.labor.modules.demand.DemandService;
import com.company.labor.modules.demand.DemandStatus;
import com.company.labor.modules.identity.MembershipRole;
import com.company.labor.modules.identity.OrganizationDtos.CreateRequest;
import com.company.labor.modules.identity.OrganizationDtos.JoinRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipDecisionRequest;
import com.company.labor.modules.identity.OrganizationDtos.CorrectionItemRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipRequestDecision;
import com.company.labor.modules.identity.OrganizationDtos.UpdateRequest;
import com.company.labor.modules.identity.OrganizationDtos.OrganizationReviewDecision;
import com.company.labor.modules.identity.OrganizationRole;
import com.company.labor.modules.identity.OrganizationService;
import com.company.labor.modules.identity.OrganizationStatus;
import com.company.labor.modules.platform.PlatformTaskService;
import com.company.labor.modules.platform.PlatformTaskType;
import com.company.labor.modules.platform.ReviewCaseType;
import com.company.labor.modules.platform.ReviewCaseHistoryDtos;
import com.company.labor.modules.platform.ReviewCaseHistoryService;
import com.company.labor.modules.platform.PlatformReviewEvidenceService;
import com.company.labor.modules.platform.ReviewEvidenceCategory;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.storage.EvidenceContentScanner;
import com.company.labor.foundation.storage.PrivateFileStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用独立 PostgreSQL 验证身份组织、资质审核、成员加入和需求发布的真实事务流程。 */
@SpringBootTest
@Import(BusinessFoundationIT.EvidenceScannerFixtures.class)
class BusinessFoundationIT {
    /** 当前测试的组织和需求数据只使用合成标识。 */
    private static final long ORGANIZATION_REVIEWER_ID = 900L;
    /** 与组织审核员分离的合成需求审核账号。 */
    private static final long DEMAND_REVIEWER_ID = 901L;

    /** 真实隔离测试数据库访问入口。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 首批组织业务服务。 */
    @Autowired
    private OrganizationService organizations;
    /** 首批需求业务服务。 */
    @Autowired
    private DemandService demands;
    /** 事项映射和申请方可见审核历史服务。 */
    @Autowired
    private ReviewCaseHistoryService reviewHistory;
    /** G5 岗位审核、认领、释放和异常恢复服务。 */
    @Autowired
    private PlatformTaskService platformTasks;
    /** G6 私有文件扫描绑定和任务预览服务。 */
    @Autowired
    private PlatformReviewEvidenceService reviewEvidence;
    /** 用于建立本人上传的私有材料。 */
    @Autowired
    private PrivateFileStorage files;

    /**
     * 复用现有集成测试对独立测试数据库及合成 JWT 密钥的校验。
     * @param registry 测试环境属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FoundationIT.properties(registry);
        registry.add("foundation.storage.max-bytes", () -> 10 * 1024 * 1024);
    }

    /** 清理仅属于本测试的业务表和审计行，不连接或清理开发数据库。 */
    @BeforeEach
    void reset() {
        ExecutionContext.clear();
        jdbc.execute("""
                TRUNCATE platform_task_claim_request, platform_task_conflict, platform_task_claim,
                    platform_review_evidence_verification, platform_appeal_review_result, platform_review_task,
                    platform_appeal_case, platform_review_correction_item, platform_review_case,
                    platform_review_evidence, foundation_file,
                    business_demand_delivery, business_review_decision, business_demand_revision,
                    business_demand, business_membership_request, business_membership,
                    business_organization_revision, business_organization,
                    foundation_idempotency, foundation_audit CASCADE
                """);
        FoundationIT.resetAuditChainForTest(jdbc);
    }

    /** 验证组织审核、成员加入、需求审核及发布形成完整可追溯状态链。 */
    @Test
    void employerOrganizationAndDemandPassIndependentReviewGates() {
        long employerId = createOrganization(101L, OrganizationRole.EMPLOYER, "EMPLOYER-TEST-101", null);
        try (var ignored = identity(101L, employerId, Set.of("organization:organizations:write"))) {
            assertEquals(OrganizationStatus.PENDING_REVIEW, organizations.submit(employerId).status());
        }
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            assertEquals(1, organizations.pendingReviews().size());
        }
        reviewOrganization(employerId, OrganizationReviewDecision.NEEDS_CORRECTION, "请补充控制权证明");
        try (var ignored = identity(101L, employerId, Set.of("organization:organizations:write"))) {
            assertEquals(2, organizations.update(employerId,
                    new UpdateRequest(3L, "合成组织补正", "EMPLOYER-TEST-101",
                            "AUTH-FIXTURE-CORRECTED", null)).applicationVersion());
            organizations.submit(employerId);
        }
        reviewOrganization(employerId, OrganizationReviewDecision.APPROVE, null);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM business_organization WHERE id = ?", String.class, employerId));

        long membershipRequest;
        try (var ignored = identity(202L, null, Set.of("organization:membership:request"))) {
            membershipRequest = organizations.join(employerId, new JoinRequest(MembershipRole.DEMAND_EDITOR));
        }
        try (var ignored = identity(101L, employerId, Set.of("organization:members:manage"))) {
            organizations.decideJoin(employerId, membershipRequest,
                    new MembershipDecisionRequest(MembershipRequestDecision.APPROVE, null));
        }

        long supplierId = createOrganization(303L, OrganizationRole.LABOR_SUPPLIER,
                "SUPPLIER-TEST-303", "QUALIFICATION-FIXTURE");
        try (var ignored = identity(303L, supplierId, Set.of("organization:organizations:write"))) {
            organizations.submit(supplierId);
        }
        reviewOrganization(supplierId, OrganizationReviewDecision.APPROVE, null);

        long demandId;
        try (var ignored = identity(202L, employerId,
                Set.of("organization:demands:write"))) {
            demandId = demands.create(employerId, "demand-create-1", demand()).id();
            assertEquals(DemandStatus.PENDING_REVIEW, demands.submit(demandId).status());
        }
        assertEquals(DemandStatus.APPROVED_PENDING_PUBLISH,
                reviewDemand(demandId, new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                        com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.APPROVE, null)).status());
        try (var ignored = identity(101L, employerId,
                Set.of("organization:demands:publish"))) {
            assertEquals(DemandStatus.OPEN_FOR_BID, demands.publish(demandId).status());
        }

        try (var ignored = identity(303L, supplierId, Set.of())) {
            var publicDemand = demands.openForBid().stream()
                    .filter(item -> item.id() == demandId)
                    .findFirst()
                    .orElseThrow();
            assertEquals(DemandStatus.OPEN_FOR_BID, publicDemand.status());
            assertEquals(null, publicDemand.relatedPartyRelation());
            assertEquals(null, publicDemand.reviewReason());
        }
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE resource_type IN ('organization', 'demand')
                """, Integer.class) >= 8);
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_demand_delivery
                WHERE tenant_id = ? AND demand_id = ? AND revision = 1
                """, Integer.class, supplierId, demandId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_organization_revision WHERE organization_id = ?
                """, Integer.class, employerId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_review_decision
                WHERE tenant_id = ? AND resource_type = 'ORGANIZATION' AND resource_id = ?
                """, Integer.class, employerId, employerId));
        try (var ignored = identity(101L, supplierId, Set.of())) {
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class, () -> demands.openForBid()).getCode());
        }
    }

    /** 验证需求被退回补正后追加新版本，再次审核才允许发布。 */
    @Test
    void demandCorrectionCreatesNewRevisionBeforeApproval() {
        long employerId = createOrganization(505L, OrganizationRole.EMPLOYER, "EMPLOYER-TEST-505", null);
        try (var ignored = identity(505L, employerId, Set.of("organization:organizations:write"))) {
            organizations.submit(employerId);
        }
        reviewOrganization(employerId, OrganizationReviewDecision.APPROVE, null);
        long demandId;
        try (var ignored = identity(505L, employerId, Set.of("organization:demands:write"))) {
            demandId = demands.create(employerId, "demand-correction-1", demand()).id();
            demands.submit(demandId);
        }
        assertEquals(DemandStatus.NEEDS_CORRECTION,
                reviewDemand(demandId, new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                        com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.NEEDS_CORRECTION,
                        "请补充供应商响应条件", List.of(new com.company.labor.modules.demand.DemandDtos
                        .CorrectionItemRequest("RESPONSE_CONDITION", "供应商响应条件", "服务口径缺少可核对边界",
                                "补充可执行的人员到岗和验收要求")))).status());
        long correctionDecisionId = jdbc.queryForObject("""
                SELECT id FROM business_review_decision
                WHERE resource_type = 'DEMAND' AND resource_id = ?
                """, Long.class, demandId);
        PlatformTaskService.AppealCreated outdatedAppeal;
        try (var ignored = identity(505L, employerId, Set.of())) {
            outdatedAppeal = platformTasks.createAppeal(correctionDecisionId, "请求独立复核补正事项");
        }
        ReviewCaseHistoryDtos.View correctionHistory;
        try (var ignored = identity(505L, employerId, Set.of())) {
            correctionHistory = reviewHistory.applicantDemandHistory(demandId);
        }
        assertEquals(1, correctionHistory.unresolvedItems().size());
        assertEquals("供应商响应条件：补充可执行的人员到岗和验收要求",
                correctionHistory.unresolvedItems().get(0));
        try (var ignored = identity(505L, employerId, Set.of("organization:demands:write"))) {
            var corrected = demands.update(demandId, 1, demand());
            assertEquals(2, corrected.revision());
            assertEquals(DemandStatus.NEEDS_CORRECTION, corrected.status());
            demands.submit(demandId);
        }
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM platform_appeal_case WHERE id = ?", String.class,
                Long.parseLong(outdatedAppeal.appealCaseId())));
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM platform_review_task WHERE id = ?", String.class,
                Long.parseLong(outdatedAppeal.taskId())));
        try (var ignored = platformIdentity(904L, "INDEPENDENT_REVIEWER")) {
            assertEquals(PlatformError.VERSION_CONFLICT.code(), assertThrows(BusinessException.class,
                    () -> platformTasks.claim(Long.parseLong(outdatedAppeal.taskId()),
                            "claim-demand-correction-obsolete-appeal")).getCode());
        }
        assertEquals(DemandStatus.APPROVED_PENDING_PUBLISH,
                reviewDemand(demandId, new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                        com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.APPROVE, null)).status());
        ReviewCaseHistoryDtos.View history;
        try (var ignored = identity(505L, employerId, Set.of())) {
            history = reviewHistory.applicantDemandHistory(demandId);
        }
        assertEquals("DEMAND", history.resourceType());
        assertEquals("APPROVED_PENDING_PUBLISH", history.currentStatus());
        assertEquals(2, history.currentVersion());
        assertEquals(2, history.versions().size());
        assertEquals("NEEDS_CORRECTION", history.versions().get(0).decision());
        assertEquals("请补充供应商响应条件", history.versions().get(0).publicReason());
        assertEquals("RESPONSE_CONDITION", history.versions().get(0).correctionItems().get(0).itemCode());
        assertTrue(history.unresolvedItems().isEmpty());
        assertEquals("APPROVE", history.versions().get(1).decision());
        assertTrue(history.versions().get(1).submittedAt() != null);
        assertFalse(history.internalView());
        assertTrue(history.events().stream().allMatch(event -> event.actorId() == null
                && event.actorRole() == null && event.reasonCode() == null));
        try (var ignored = identity(606L, 999_999L, Set.of())) {
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> reviewHistory.applicantDemandHistory(demandId)).getCode());
        }
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_review_decision
                WHERE resource_type = 'DEMAND' AND resource_id = ?
                """, Integer.class, demandId));
    }

    /** 验证多个组织事项相互隔离、补正新版本可追溯且申请方时间线不披露内部操作者字段。 */
    @Test
    void organizationReviewHistorySeparatesCasesAndProtectsInternalAuditFields() {
        long firstOrganization = createOrganization(611L, OrganizationRole.EMPLOYER,
                "EMPLOYER-HISTORY-611", null);
        long secondOrganization = createOrganization(612L, OrganizationRole.EMPLOYER,
                "EMPLOYER-HISTORY-612", null);
        try (var ignored = identity(611L, firstOrganization, Set.of("organization:organizations:write"))) {
            organizations.submit(firstOrganization);
        }
        try (var ignored = identity(612L, secondOrganization, Set.of("organization:organizations:write"))) {
            organizations.submit(secondOrganization);
        }
        String correctionReason = "请补充控制权证明";
        reviewOrganization(firstOrganization, OrganizationReviewDecision.NEEDS_CORRECTION, correctionReason,
                List.of(new CorrectionItemRequest("AUTHORITY", "经办授权链", "无法确认签署人与组织的授权关系",
                        "补充有效授权文件并说明授权范围"),
                        new CorrectionItemRequest("REGISTRATION", "登记信息", "登记名称与提交材料不一致",
                                "更正登记名称或补充差异说明")));

        ReviewCaseHistoryDtos.View correctionHistory;
        ReviewCaseHistoryDtos.View unrelatedHistory;
        try (var ignored = identity(611L, firstOrganization, Set.of())) {
            correctionHistory = reviewHistory.applicantOrganizationHistory(firstOrganization);
        }
        try (var ignored = identity(612L, secondOrganization, Set.of())) {
            unrelatedHistory = reviewHistory.applicantOrganizationHistory(secondOrganization);
        }

        assertNotEquals(correctionHistory.reviewCaseId(), unrelatedHistory.reviewCaseId());
        assertEquals("ORGANIZATION", correctionHistory.resourceType());
        assertEquals("NEEDS_CORRECTION", correctionHistory.currentStatus());
        assertEquals(1, correctionHistory.currentVersion());
        assertTrue(correctionHistory.unresolvedItems().contains("经办授权链：补充有效授权文件并说明授权范围"));
        assertEquals("NEEDS_CORRECTION", correctionHistory.versions().get(0).decision());
        assertEquals(correctionReason, correctionHistory.versions().get(0).publicReason());
        assertEquals(2, correctionHistory.versions().get(0).correctionItems().size());
        assertEquals("AUTHORITY", correctionHistory.versions().get(0).correctionItems().get(0).itemCode());
        assertEquals("补充有效授权文件并说明授权范围",
                correctionHistory.versions().get(0).correctionItems().get(0).applicantAction());
        assertEquals(2, correctionHistory.unresolvedItems().size());
        assertEquals(1, unrelatedHistory.versions().size());
        assertNull(unrelatedHistory.versions().get(0).decision());
        assertTrue(correctionHistory.events().stream().anyMatch(event -> "organization:review".equals(event.action())));
        assertTrue(correctionHistory.events().stream().allMatch(event -> event.actorId() == null
                && event.actorRole() == null && event.reasonCode() == null));
        assertFalse(correctionHistory.internalView());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:review_case:history:read' AND outcome = 'SUCCESS'
                  AND review_case_id = ? AND purpose_code = 'APPLICANT_HISTORY'
                """, Integer.class, correctionHistory.reviewCaseId()));

        try (var ignored = identity(612L, secondOrganization, Set.of())) {
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> reviewHistory.applicantOrganizationHistory(firstOrganization)).getCode());
        }
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> reviewHistory.applicantOrganizationHistory(firstOrganization)).getCode());
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:review_case:history:denied' AND outcome = 'DENIED'
                  AND resource_id = ? AND actor_id = 612
                """, Integer.class, Long.toString(firstOrganization)));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:review_case:history:denied' AND outcome = 'DENIED'
                  AND resource_id = ? AND actor_id = ?
                """, Integer.class, Long.toString(firstOrganization), ORGANIZATION_REVIEWER_ID));

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'organization:create' AND resource_id = ? AND review_case_id IS NULL
                """, Integer.class, Long.toString(firstOrganization)));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'organization:submit' AND resource_id = ? AND review_case_id = ?
                """, Integer.class, Long.toString(firstOrganization), correctionHistory.reviewCaseId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE platform_review_case SET initial_version = 2 WHERE id = ?
                """, correctionHistory.reviewCaseId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                DELETE FROM platform_review_case WHERE id = ?
                """, correctionHistory.reviewCaseId()));

        try (var ignored = identity(611L, firstOrganization, Set.of("organization:organizations:write"))) {
            organizations.update(firstOrganization,
                    new UpdateRequest(3L, "补正后的合成组织", "EMPLOYER-HISTORY-611",
                            "AUTH-FIXTURE-CORRECTED", null));
            organizations.submit(firstOrganization);
        }
        ReviewCaseHistoryDtos.View resubmittedHistory;
        try (var ignored = identity(611L, firstOrganization, Set.of())) {
            resubmittedHistory = reviewHistory.applicantOrganizationHistory(firstOrganization);
        }
        assertEquals(correctionHistory.reviewCaseId(), resubmittedHistory.reviewCaseId());
        assertEquals(2, resubmittedHistory.currentVersion());
        assertEquals(2, resubmittedHistory.versions().size());
        assertEquals(correctionReason, resubmittedHistory.versions().get(0).publicReason());
        assertEquals(2, resubmittedHistory.versions().get(0).correctionItems().size());
        assertEquals(2, resubmittedHistory.versions().get(1).resourceVersion());
        assertTrue(resubmittedHistory.versions().get(1).submittedAt() != null);
        assertTrue(resubmittedHistory.unresolvedItems().isEmpty());
    }

    /** 补正决定必须持久化逐项可执行事项，不能只保存一段笼统意见。 */
    @Test
    void organizationCorrectionDecisionRequiresItemizedActions() {
        long organizationId = createOrganization(613L, OrganizationRole.EMPLOYER,
                "EMPLOYER-CORRECTION-613", null);
        try (var ignored = identity(613L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.submit(organizationId);
        }
        long taskId = findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 1L);
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(taskId, "org-itemized-correction-613");
            var missingItems = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.NEEDS_CORRECTION, "请完善申请材料", List.of());
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), assertThrows(BusinessException.class,
                    () -> organizations.reviewTask(taskId, missingItems)).getCode());
            var blankAction = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.NEEDS_CORRECTION, "请完善申请材料",
                    List.of(new CorrectionItemRequest("AUTH", "授权材料", "缺少有效授权说明", "   ")));
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), assertThrows(BusinessException.class,
                    () -> organizations.reviewTask(taskId, blankAction)).getCode());
            var duplicateCodes = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.NEEDS_CORRECTION, "请完善申请材料",
                    List.of(new CorrectionItemRequest("AUTH", "授权材料", "缺少有效授权说明", "补交授权说明"),
                            new CorrectionItemRequest(" AUTH ", "登记材料", "登记信息不清晰", "补充登记说明")));
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), assertThrows(BusinessException.class,
                    () -> organizations.reviewTask(taskId, duplicateCodes)).getCode());
            var tooManyItems = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.NEEDS_CORRECTION, "请完善申请材料",
                    java.util.stream.IntStream.range(0, 21).mapToObj(index -> new CorrectionItemRequest(
                            "ITEM-" + index, "问题 " + index, "问题说明 " + index, "补正动作 " + index)).toList());
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), assertThrows(BusinessException.class,
                    () -> organizations.reviewTask(taskId, tooManyItems)).getCode());
            var correctionOnApproval = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.APPROVE, null,
                    List.of(new CorrectionItemRequest("AUTH", "授权材料", "说明", "补交说明")));
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), assertThrows(BusinessException.class,
                    () -> organizations.reviewTask(taskId, correctionOnApproval)).getCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_review_correction_item",
                Integer.class));
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT status FROM business_organization WHERE id = ?", String.class, organizationId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_review_decision
                WHERE resource_type = 'ORGANIZATION' AND resource_id = ?
                """, Integer.class, organizationId));
        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT status FROM platform_review_task WHERE id = ?", String.class, taskId));

        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            var validItems = new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                    OrganizationReviewDecision.NEEDS_CORRECTION, "请完善申请材料",
                    List.of(new CorrectionItemRequest("AUTH", "授权材料", "缺少有效授权说明", "补交授权说明")));
            organizations.reviewTask(taskId, validItems);
        }
        long correctionItemId = jdbc.queryForObject(
                "SELECT id FROM platform_review_correction_item WHERE resource_id = ?", Long.class, organizationId);
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:review:correction-items:recorded'
                  AND resource_id = ? AND outcome = 'SUCCESS'
                """, Integer.class, Long.toString(organizationId)));
        assertEquals("NEEDS_CORRECTION", jdbc.queryForObject(
                "SELECT status FROM business_organization WHERE id = ?", String.class, organizationId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE platform_review_correction_item SET title = '篡改' WHERE id = ?", correctionItemId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM platform_review_correction_item WHERE id = ?", correctionItemId));
    }

    /** 验证待审核组织和跨租户伪造不能绕过服务端门禁。 */
    @Test
    void unapprovedOrganizationsAndTenantForgeryAreRejected() {
        long employerId = createOrganization(404L, OrganizationRole.EMPLOYER, "EMPLOYER-TEST-404", null);
        try (var ignored = identity(404L, employerId, Set.of("organization:demands:write"))) {
                    assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class,
                            () -> demands.create(employerId, "demand-create-unauthorized", demand())).getCode());
        }
        try (var ignored = identity(404L, 999_999L, Set.of("organization:organizations:write"))) {
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class, () -> organizations.submit(employerId)).getCode());
        }
    }

    /** 验证任务按提交创建、原子认领、同人幂等、他人隔离、释放和异常恢复闭环。 */
    @Test
    void platformReviewTasksRequireClaimAndSupportReleaseAndRecovery() {
        long organizationId = createOrganization(710L, OrganizationRole.EMPLOYER, "EMPLOYER-TASK-710", null);
        try (var ignored = identity(710L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.submit(organizationId);
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_review_task
                WHERE resource_type = 'ORGANIZATION' AND resource_id = ? AND resource_version = 1
                """, Integer.class, organizationId));
        long taskId = findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 1L);
        java.sql.Timestamp originalCreatedAt = jdbc.queryForObject(
                "SELECT created_at FROM platform_review_task WHERE id = ?", java.sql.Timestamp.class, taskId);

        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            PlatformTaskService.ClaimResult first = platformTasks.claim(taskId, "claim-710-first");
            PlatformTaskService.ClaimResult retry = platformTasks.claim(taskId, "claim-710-first");
            assertEquals(first.claimId(), retry.claimId());
            assertTrue(retry.active());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM platform_task_claim
                    WHERE task_id = ? AND status = 'ACTIVE'
                    """, Integer.class, taskId));
            assertTrue(platformTasks.details(taskId).history().internalView());
            try (var otherReviewer = platformIdentity(902L, "ORGANIZATION_REVIEWER")) {
                assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                        () -> platformTasks.details(taskId)).getCode());
                assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                        () -> organizations.review(organizationId,
                                new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                                        OrganizationReviewDecision.APPROVE, null))).getCode());
            }
            assertEquals(taskId, Long.parseLong(platformTasks.release(taskId, "轮班交接")));
            assertFalse(platformTasks.claim(taskId, "claim-710-first").active());
        }

        try (var ignored = platformIdentity(902L, "ORGANIZATION_REVIEWER")) {
            PlatformTaskService.ClaimResult secondOwner = platformTasks.claim(taskId, "claim-710-second-owner");
            assertTrue(secondOwner.active());
        }
        try (var ignored = platformIdentity(903L, "TASK_RECOVERY")) {
            assertEquals(1, platformTasks.recoveryQueue().size());
            assertEquals(taskId, Long.parseLong(platformTasks.recover(taskId, "值班账号停用")));
        }
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM platform_review_task WHERE id = ?", String.class, taskId));
        assertEquals("RECOVERED", jdbc.queryForObject(
                "SELECT status FROM platform_task_claim WHERE task_id = ? AND actor_id = ? ORDER BY id DESC LIMIT 1",
                String.class, taskId, 902L));
        assertEquals(originalCreatedAt, jdbc.queryForObject(
                "SELECT created_at FROM platform_review_task WHERE id = ?", java.sql.Timestamp.class, taskId));
        assertEquals("值班账号停用", jdbc.queryForObject(
                "SELECT close_reason FROM platform_task_claim WHERE task_id = ? AND actor_id = ? ORDER BY id DESC LIMIT 1",
                String.class, taskId, 902L));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_task_recovery_notice n
                JOIN platform_task_claim c ON c.id = n.claim_id AND c.task_id = n.task_id
                WHERE n.task_id = ? AND n.recipient_account_id = ? AND c.actor_id = ?
                  AND c.claimed_at <= n.created_at
                """, Integer.class, taskId, 902L, 902L));
        assertEquals(1, jdbc.update("""
                UPDATE platform_authorization_grant SET revoked_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND revoked_at IS NULL
                """, 902L));
        try (var ignored = identity(902L, null, Set.of())) {
            var notices = platformTasks.recoveryNotices();
            assertEquals(1, notices.size());
            assertEquals(Long.toString(taskId), notices.get(0).taskId());
            assertEquals("ORGANIZATION_REVIEW", notices.get(0).taskType());
        }
        try (var ignored = identity(903L, null, Set.of())) {
            assertTrue(platformTasks.recoveryNotices().isEmpty());
        }

        try (var ignored = platformIdentity(902L, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(taskId, "claim-710-party-recusal");
        }
        try (var ignored = identity(710L, organizationId, Set.of())) {
            platformTasks.requestPartyRecusal(taskId, "与组织存在未披露的合作关系");
        }
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM platform_review_task WHERE id = ?", String.class, taskId));
        try (var ignored = platformIdentity(902L, "ORGANIZATION_REVIEWER")) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(), assertThrows(BusinessException.class,
                    () -> platformTasks.claim(taskId, "claim-710-party-recusal-retry")).getCode());
        }
        try (var ignored = platformIdentity(905L, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(taskId, "claim-710-self-recusal");
            platformTasks.declareRecusal(taskId, "需要回避的个人关系");
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(), assertThrows(BusinessException.class,
                    () -> platformTasks.claim(taskId, "claim-710-self-recusal-retry")).getCode());
        }
        try (var ignored = platformIdentity(906L, "ORGANIZATION_REVIEWER")) {
            assertTrue(platformTasks.claim(taskId, "claim-710-final-owner").active());
        }
    }

    /** 验证两个具备相同岗位的审核员并发认领时数据库只允许一人占用。
     * @throws Exception 并发线程启动、等待或结果读取失败
     */
    @Test
    void concurrentReviewClaimsHaveExactlyOneWinner() throws Exception {
        long organizationId = createOrganization(712L, OrganizationRole.EMPLOYER, "EMPLOYER-RACE-712", null);
        try (var ignored = identity(712L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.submit(organizationId);
        }
        long taskId = findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 1L);
        try (var ignored = platformIdentity(905L, "ORGANIZATION_REVIEWER")) {
            // 先建立真实的岗位授权，避免把并发测试退化为上下文字符串检查。
        }
        try (var ignored = platformIdentity(906L, "ORGANIZATION_REVIEWER")) {
            // 先建立真实的岗位授权，避免把并发测试退化为上下文字符串检查。
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimAttempt> first = executor.submit(() -> claimAtBarrier(905L, taskId, "claim-712-a",
                    ready, start));
            Future<ClaimAttempt> second = executor.submit(() -> claimAtBarrier(906L, taskId, "claim-712-b",
                    ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            ClaimAttempt firstResult = first.get(10, TimeUnit.SECONDS);
            ClaimAttempt secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (firstResult.success() ? 1 : 0) + (secondResult.success() ? 1 : 0));
            String conflictCode = Integer.toString(PlatformError.DATA_CONFLICT.code());
            assertEquals(1, (conflictCode.equals(firstResult.errorCode()) ? 1 : 0)
                    + (conflictCode.equals(secondResult.errorCode()) ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_task_claim WHERE task_id = ? AND status = 'ACTIVE'
                """, Integer.class, taskId));
    }

    /** 验证原审核参与人不得复核，待处理申诉不改变原业务决定且由独立岗位完成。 */
    @Test
    void appealReviewRequiresIndependentReviewerAndDoesNotChangeOriginalDecision() {
        long organizationId = createOrganization(711L, OrganizationRole.EMPLOYER, "EMPLOYER-APPEAL-711", null);
        try (var ignored = identity(711L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.submit(organizationId);
        }
        long decisionId;
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 1L), "claim-711-original");
            organizations.review(organizationId,
                    new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                            OrganizationReviewDecision.REJECT, "授权链条无法核实"));
            decisionId = jdbc.queryForObject("""
                    SELECT id FROM business_review_decision
                    WHERE resource_type = 'ORGANIZATION' AND resource_id = ?
                    """, Long.class, organizationId);
        }

        PlatformTaskService.AppealCreated appeal;
        try (var ignored = identity(711L, organizationId, Set.of())) {
            appeal = platformTasks.createAppeal(decisionId, "补充材料后申请独立复核");
        }
        assertEquals("PENDING", appeal.status());
        try (var ignored = identity(711L, organizationId, Set.of())) {
            ReviewCaseHistoryDtos.View pending = reviewHistory.applicantOrganizationHistory(organizationId);
            assertEquals(1, pending.appeals().size());
            assertEquals("PENDING", pending.appeals().get(0).status());
            assertNull(pending.appeals().get(0).outcome());
            assertEquals(Long.toString(decisionId), pending.versions().get(0).decisionId());
        }

        jdbc.update("""
                UPDATE platform_authorization_grant SET revoked_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND revoked_at IS NULL
                """, ORGANIZATION_REVIEWER_ID);
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "INDEPENDENT_REVIEWER")) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(), assertThrows(BusinessException.class,
                    () -> platformTasks.claim(Long.parseLong(appeal.taskId()), "claim-711-conflicted-reviewer"))
                    .getCode());
            assertEquals(1, platformTasks.queue(PlatformTaskType.APPEAL_REVIEW).size());
        }
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM platform_appeal_case WHERE id = ?", String.class,
                Long.parseLong(appeal.appealCaseId())));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_review_task
                WHERE id = ? AND status = 'PENDING'
                """, Integer.class, Long.parseLong(appeal.taskId())));

        try (var ignored = platformIdentity(904L, "INDEPENDENT_REVIEWER")) {
            platformTasks.claim(Long.parseLong(appeal.taskId()), "claim-711-independent");
            PlatformTaskService.AppealReviewResult result = platformTasks.completeAppealReview(
                    Long.parseLong(appeal.taskId()), PlatformTaskService.AppealOutcome.REQUEST_RECONSIDERATION,
                    "复核材料支持重新审核");
            assertEquals("REQUEST_RECONSIDERATION", result.outcome());
        }
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM business_organization WHERE id = ?", String.class, organizationId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_review_decision WHERE id = ? AND decision = 'REJECT'
                """, Integer.class, decisionId));
        assertEquals("REVIEWED", jdbc.queryForObject(
                "SELECT status FROM platform_appeal_case WHERE id = ?", String.class,
                Long.parseLong(appeal.appealCaseId())));
        try (var ignored = identity(711L, organizationId, Set.of())) {
            ReviewCaseHistoryDtos.View reviewed = reviewHistory.applicantOrganizationHistory(organizationId);
            assertEquals("REVIEWED", reviewed.appeals().get(0).status());
            assertEquals("REQUEST_RECONSIDERATION", reviewed.appeals().get(0).outcome());
            assertTrue(reviewed.appeals().get(0).reviewedAt() != null);
            assertEquals("REJECTED", reviewed.currentStatus());
        }
    }

    /** 验证材料本人上传、魔数/扫描/结构检查、版本绑定、认领撤权、跨组织隔离和预览留痕。 */
    @Test
    void evidenceRequiresCleanPreviewableFileAndCurrentClaimedTask() throws Exception {
        long organizationId = createOrganization(720L, OrganizationRole.EMPLOYER, "EMPLOYER-EVIDENCE-720", null);
        byte[] pdf = validPdf();
        PrivateFileStorage.Metadata validUpload = storeEvidence(720L, organizationId, pdf, "authorization.pdf");
        String evidenceId;
        try (var ignored = identity(720L, organizationId, Set.of("organization:organizations:write"))) {
            var attached = reviewEvidence.attach(ReviewCaseType.ORGANIZATION, organizationId, 1L,
                    validUpload.id(), ReviewEvidenceCategory.AUTHORIZATION);
            evidenceId = attached.evidenceId();
            assertEquals(1L, attached.resourceVersion());
            assertEquals(ReviewEvidenceCategory.AUTHORIZATION, attached.category());

            PrivateFileStorage.Metadata malware = storeEvidence(720L, organizationId,
                    "%PDF-1.4\nTEST-MALWARE\n%%EOF".getBytes(StandardCharsets.US_ASCII), "malware.pdf");
            assertThrows(BusinessException.class, () -> reviewEvidence.attach(ReviewCaseType.ORGANIZATION,
                    organizationId, 1L, malware.id(), ReviewEvidenceCategory.SUPPORTING_DOCUMENT));

            PrivateFileStorage.Metadata spoofed = storeEvidence(720L, organizationId,
                    "<html>not a PDF</html>".getBytes(StandardCharsets.US_ASCII), "spoofed.pdf");
            assertThrows(BusinessException.class, () -> reviewEvidence.attach(ReviewCaseType.ORGANIZATION,
                    organizationId, 1L, spoofed.id(), ReviewEvidenceCategory.SUPPORTING_DOCUMENT));

            PrivateFileStorage.Metadata corrupt = storeEvidence(720L, organizationId,
                    "%PDF-1.4\nnot a structurally valid PDF\n%%EOF".getBytes(StandardCharsets.US_ASCII),
                    "corrupt.pdf");
            assertThrows(BusinessException.class, () -> reviewEvidence.attach(ReviewCaseType.ORGANIZATION,
                    organizationId, 1L, corrupt.id(), ReviewEvidenceCategory.SUPPORTING_DOCUMENT));

            PrivateFileStorage.Metadata unavailableScanner = storeEvidence(720L, organizationId,
                    "%PDF-1.4\nSCANNER-UNAVAILABLE\n%%EOF".getBytes(StandardCharsets.US_ASCII),
                    "scanner-unavailable.pdf");
            assertThrows(BusinessException.class, () -> reviewEvidence.attach(ReviewCaseType.ORGANIZATION,
                    organizationId, 1L, unavailableScanner.id(), ReviewEvidenceCategory.SUPPORTING_DOCUMENT));

            PrivateFileStorage.Metadata anotherMemberUpload = storeEvidence(722L, organizationId, pdf,
                    "other-member.pdf");
            assertThrows(BusinessException.class, () -> reviewEvidence.attach(ReviewCaseType.ORGANIZATION,
                    organizationId, 1L, anotherMemberUpload.id(), ReviewEvidenceCategory.SUPPORTING_DOCUMENT));
            try (var otherMember = identity(722L, organizationId, Set.of())) {
                assertThrows(BusinessException.class,
                        () -> reviewEvidence.listForApplicant(ReviewCaseType.ORGANIZATION, organizationId, 1L));
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_review_evidence", Integer.class));
        assertEquals("NOT_VERIFIED", jdbc.queryForObject(
                "SELECT authenticity_status FROM platform_review_evidence WHERE id = ?", String.class,
                Long.parseLong(evidenceId)));
        assertEquals(5, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:evidence:attached' AND outcome = 'DENIED'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:evidence:accessed' AND outcome = 'DENIED'
                  AND reason_code = 'APPLICANT_SCOPE_DENIED'
                """, Integer.class));

        long secondOrganization = createOrganization(721L, OrganizationRole.EMPLOYER,
                "EMPLOYER-EVIDENCE-721", null);
        PrivateFileStorage.Metadata otherOrganizationUpload = storeEvidence(721L, secondOrganization, pdf,
                "other-organization.pdf");
        String otherEvidenceId;
        try (var ignored = identity(721L, secondOrganization, Set.of("organization:organizations:write"))) {
            otherEvidenceId = reviewEvidence.attach(ReviewCaseType.ORGANIZATION, secondOrganization, 1L,
                    otherOrganizationUpload.id(), ReviewEvidenceCategory.AUTHORIZATION).evidenceId();
        }

        try (var ignored = identity(720L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.submit(organizationId);
        }
        long firstTask = findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 1L);
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            assertThrows(BusinessException.class, () -> reviewEvidence.listForTask(firstTask));
            platformTasks.claim(firstTask, "evidence-review-720-v1");
            var summaries = reviewEvidence.listForTask(firstTask);
            assertEquals(1, summaries.size());
            assertEquals("NOT_VERIFIED", summaries.get(0).authenticityStatus());
            byte[] preview = reviewEvidence.preview(firstTask, Long.parseLong(evidenceId), 0);
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
                    Arrays.copyOf(preview, 8));
            assertThrows(BusinessException.class,
                    () -> reviewEvidence.preview(firstTask, Long.parseLong(otherEvidenceId), 0));
            organizations.review(organizationId,
                    new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(
                            OrganizationReviewDecision.NEEDS_CORRECTION, "请补齐授权链材料",
                            List.of(new CorrectionItemRequest("AUTHORITY", "经办授权链", "授权关系尚无法核实",
                                    "补充授权文件并注明授权范围"))));
        }

        PrivateFileStorage.Metadata correctedUpload = storeEvidence(720L, organizationId, pdf,
                "corrected-authorization.pdf");
        String correctedEvidenceId;
        try (var ignored = identity(720L, organizationId, Set.of("organization:organizations:write"))) {
            organizations.update(organizationId, new UpdateRequest(currentOrganizationRowVersion(organizationId),
                    "合成组织补正", "EMPLOYER-EVIDENCE-720",
                    "AUTH-FIXTURE-CORRECTED", null));
            var corrected = reviewEvidence.attach(ReviewCaseType.ORGANIZATION, organizationId, 2L,
                    correctedUpload.id(), ReviewEvidenceCategory.AUTHORIZATION);
            correctedEvidenceId = corrected.evidenceId();
            assertEquals(2L, corrected.resourceVersion());
            organizations.submit(organizationId);
        }
        long secondTask = findTaskId(ReviewCaseType.ORGANIZATION, organizationId, 2L);
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(secondTask, "evidence-review-720-v2");
            var currentEvidence = reviewEvidence.listForTask(secondTask);
            assertEquals(1, currentEvidence.size());
            assertEquals(correctedEvidenceId, currentEvidence.get(0).evidenceId());
            assertThrows(BusinessException.class,
                    () -> reviewEvidence.preview(secondTask, Long.parseLong(evidenceId), 0));
            platformTasks.release(secondTask, "验证释放后材料访问立即关闭");
            assertThrows(BusinessException.class, () -> reviewEvidence.listForTask(secondTask));
        }
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(secondTask, "evidence-review-720-v2-reclaim");
            var currentEvidence = reviewEvidence.listForTask(secondTask);
            assertEquals(1, currentEvidence.size());
            assertEquals(2L, currentEvidence.get(0).resourceVersion());
            assertEquals("NOT_VERIFIED", currentEvidence.get(0).authenticityStatus());
        }
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM foundation_audit
                WHERE action = 'platform:evidence:accessed' AND outcome IN ('SUCCESS', 'DENIED')
                  AND reason_code IN ('EVIDENCE_LIST', 'EVIDENCE_PREVIEW', 'EVIDENCE_NOT_IN_TASK_VERSION',
                                      'EVIDENCE_ACCESS_DENIED', 'TASK_SCOPE_DENIED')
                """, Integer.class) >= 7);

        long demandOrganization = createOrganization(723L, OrganizationRole.EMPLOYER,
                "EMPLOYER-DEMAND-EVIDENCE-723", null);
        try (var ignored = identity(723L, demandOrganization, Set.of("organization:organizations:write"))) {
            organizations.submit(demandOrganization);
        }
        reviewOrganization(demandOrganization, OrganizationReviewDecision.APPROVE, null);
        long editorMembership;
        try (var ignored = identity(724L, null, Set.of("organization:membership:request"))) {
            editorMembership = organizations.join(demandOrganization, new JoinRequest(MembershipRole.DEMAND_EDITOR));
        }
        try (var ignored = identity(723L, demandOrganization, Set.of("organization:members:manage"))) {
            organizations.decideJoin(demandOrganization, editorMembership,
                    new MembershipDecisionRequest(MembershipRequestDecision.APPROVE, null));
        }
        PrivateFileStorage.Metadata demandUpload = storeEvidence(724L, demandOrganization, pdf,
                "demand-authorization.pdf");
        long demandId;
        try (var ignored = identity(724L, demandOrganization, Set.of("organization:demands:write"))) {
            demandId = demands.create(demandOrganization, "demand-evidence-723", demand()).id();
            var attached = reviewEvidence.attach(ReviewCaseType.DEMAND, demandId, 1L, demandUpload.id(),
                    ReviewEvidenceCategory.SUPPORTING_DOCUMENT);
            assertEquals(1L, attached.resourceVersion());
            assertEquals(ReviewEvidenceCategory.SUPPORTING_DOCUMENT, attached.category());
            demands.submit(demandId);
        }
        long demandTask = findTaskId(ReviewCaseType.DEMAND, demandId, 1L);
        Long demandEvidenceId = jdbc.queryForObject("""
                SELECT id FROM platform_review_evidence
                WHERE tenant_id = ? AND resource_type = 'DEMAND' AND resource_id = ? AND resource_version = 1
                """, Long.class, demandOrganization, demandId);
        try (var ignored = platformIdentity(DEMAND_REVIEWER_ID, "DEMAND_REVIEWER")) {
            platformTasks.claim(demandTask, "demand-evidence-723-v1");
            assertEquals(1, reviewEvidence.listForTask(demandTask).size());
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
                    Arrays.copyOf(reviewEvidence.preview(demandTask, demandEvidenceId, 0), 8));
        }
    }

    /** 建立只含一页、可安全栅格化的合成 PDF。
     * @return PDF 字节
     * @throws IOException PDFBox 写入失败
     */
    private byte[] validPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    /** 在指定合成组织和上传账号上下文中写入私有测试材料。
     * @param actor 上传账号编号
     * @param tenant 文件所属组织
     * @param content 合成材料字节
     * @param name 仅供接口元数据使用的测试文件名
     * @return 私有存储返回的文件引用
     */
    private PrivateFileStorage.Metadata storeEvidence(long actor, long tenant, byte[] content, String name) {
        try (var ignored = identity(actor, tenant, Set.of())) {
            return files.store(new ByteArrayInputStream(content), name);
        }
    }

    /** 创建合成组织并让申请人持有初始管理员成员关系。 */
    private long createOrganization(long actor, OrganizationRole role, String registrationId, String qualification) {
        try (var ignored = identity(actor, null, Set.of("organization:create"))) {
            return organizations.create(new CreateRequest(role, "合成组织", registrationId, "AUTH-FIXTURE",
                    qualification)).id();
        }
    }

    /** 使用与申请人不同的合成平台审核身份认领并处理组织结论。
     * @param organizationId 组织编号
     * @param decision 组织审核决定
     * @param reason 审核说明
     */
    private void reviewOrganization(long organizationId, OrganizationReviewDecision decision, String reason) {
        List<CorrectionItemRequest> corrections = decision == OrganizationReviewDecision.NEEDS_CORRECTION
                ? List.of(new CorrectionItemRequest("GENERAL", "申请材料补正", reason,
                        "根据审核意见补充或更正材料")) : List.of();
        reviewOrganization(organizationId, decision, reason, corrections);
    }

    /** 使用与申请人不同的平台审核身份提交指定的逐项补正清单。 */
    private void reviewOrganization(long organizationId, OrganizationReviewDecision decision, String reason,
                                    List<CorrectionItemRequest> corrections) {
        try (var ignored = platformIdentity(ORGANIZATION_REVIEWER_ID, "ORGANIZATION_REVIEWER")) {
            platformTasks.claim(findTaskId(ReviewCaseType.ORGANIZATION, organizationId, currentOrganizationVersion(
                    organizationId)), "org-review-" + organizationId + "-" + currentOrganizationVersion(organizationId));
            organizations.review(organizationId,
                    new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(decision, reason,
                            corrections));
        }
    }

    /** 以需求审核员身份认领当前需求版本后写入审核决定。
     * @param demandId 需求编号
     * @param request 需求审核决定
     * @return 审核后的需求视图
     */
    private DemandDtos.View reviewDemand(long demandId, DemandDtos.ReviewRequest request) {
        try (var ignored = platformIdentity(DEMAND_REVIEWER_ID, "DEMAND_REVIEWER")) {
            long version = currentDemandVersion(demandId);
            platformTasks.claim(findTaskId(ReviewCaseType.DEMAND, demandId, version),
                    "demand-review-" + demandId + "-" + version);
            return demands.review(demandId, request);
        }
    }

    /** 以真实业务资源和版本定位岗位任务，测试不依赖其他队列事项的排序。
     * @param caseType 事项资源类型
     * @param resourceId 业务资源编号
     * @param resourceVersion 业务资源版本
     * @return 匹配任务编号
     */
    private long findTaskId(ReviewCaseType caseType, long resourceId, long resourceVersion) {
        Long taskId = jdbc.queryForObject("""
                SELECT id FROM platform_review_task
                WHERE resource_type = ? AND resource_id = ? AND resource_version = ?
                """, Long.class, caseType.name(), resourceId, resourceVersion);
        return taskId == null ? 0L : taskId;
    }

    /** 读取测试组织当前申请版本，用于定位对应的审核任务。
     * @param organizationId 组织编号
     * @return 当前申请版本
     */
    private long currentOrganizationVersion(long organizationId) {
        Long version = jdbc.queryForObject(
                "SELECT application_version FROM business_organization WHERE id = ?", Long.class, organizationId);
        return version == null ? 0L : version;
    }

    /** 读取组织实体的乐观锁版本，与审核材料使用的申请版本分开。
     * @param organizationId 组织编号
     * @return 组织资料行版本
     */
    private long currentOrganizationRowVersion(long organizationId) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM business_organization WHERE id = ?", Long.class, organizationId);
        return version == null ? 0L : version;
    }

    /** 读取测试需求当前修订号，用于定位版本绑定的审核任务。
     * @param demandId 需求编号
     * @return 当前需求修订号
     */
    private long currentDemandVersion(long demandId) {
        Long version = jdbc.queryForObject(
                "SELECT current_revision FROM business_demand WHERE id = ?", Long.class, demandId);
        return version == null ? 0L : version;
    }

    /** 在线程屏障后以独立平台身份尝试认领，并返回成功或稳定冲突码。
     * @param actorId 当前认领账号
     * @param taskId 竞争认领的岗位任务
     * @param idempotencyKey 本次请求幂等键
     * @param ready 双线程都准备完成的同步信号
     * @param start 允许两线程并发执行的同步信号
     * @return 认领成功或冲突结果
     * @throws InterruptedException 测试线程等待屏障被中断
     */
    private ClaimAttempt claimAtBarrier(long actorId, long taskId, String idempotencyKey,
                                        CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        try (var ignored = platformIdentity(actorId, "ORGANIZATION_REVIEWER")) {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent claim barrier was not released");
            }
            try {
                return new ClaimAttempt(true, null, platformTasks.claim(taskId, idempotencyKey).claimId());
            } catch (BusinessException exception) {
                return new ClaimAttempt(false, Integer.toString(exception.getCode()), null);
            }
        }
    }

    /** 保存并发认领测试中的稳定结果，不含任何业务详情。
     * @param success 是否成功认领
     * @param errorCode 冲突时的稳定业务错误码
     * @param claimId 成功时的认领编号
     */
    private record ClaimAttempt(boolean success, String errorCode, String claimId) {
    }

    /** 创建符合已确认必填字段和时间关系的合成需求版本。 */
    private UpsertRequest demand() {
        Instant start = Instant.now().plusSeconds(86_400);
        return new UpsertRequest("实际服务、签约和付款主体关系待合同核实",
                "合成外包服务范围，不对应真实客户或项目", "测试地点", start,
                start.plusSeconds(86_400 * 30), "合成需求", new BigDecimal("12.500000"), "人天",
                "供应商需说明可供数量与到岗计划", "OPEN_QUOTE", "按经双方确认的有效人天",
                "月结", start.minusSeconds(3600), start.plusSeconds(86_400 * 2),
                "按已披露条件人工评审，不自动评分");
    }

    /**
     * 建立仅在当前测试线程有效的身份上下文，调用方必须通过 try-with-resources 关闭。
     * @param actor 受测操作者
     * @param tenant 已选择组织，可为空
     * @param permissions 测试签发的最小权限
     * @return 身份作用域
     */
    private ExecutionContext.Scope identity(long actor, Long tenant, Set<String> permissions) {
        jdbc.update("INSERT INTO auth_account(id, status) VALUES (?, 'ACTIVE') ON CONFLICT (id) DO NOTHING", actor);
        return ExecutionContext.open(new ExecutionContext.Identity(actor, tenant, permissions), "business-it");
    }

    /** 为平台审核用例建立与被测角色目录一致的实时授权和平台会话。 */
    private ExecutionContext.Scope platformIdentity(long actor, String roleCode) {
        jdbc.update("INSERT INTO auth_account(id, status) VALUES (?, 'ACTIVE') ON CONFLICT (id) DO NOTHING", actor);
        jdbc.update("""
                UPDATE platform_authorization_grant SET revoked_at = CURRENT_TIMESTAMP
                WHERE account_id = ? AND role_code <> ? AND revoked_at IS NULL
                """, actor, roleCode);
        jdbc.update("""
                INSERT INTO platform_authorization_grant(account_id, role_code, expires_at)
                SELECT ?, ?, CURRENT_TIMESTAMP + interval '1 day'
                WHERE NOT EXISTS (
                    SELECT 1 FROM platform_authorization_grant
                    WHERE account_id = ? AND role_code = ? AND revoked_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP
                )
                """, actor, roleCode, actor, roleCode);
        Set<String> permissions = Set.copyOf(jdbc.queryForList(
                "SELECT permission_code FROM platform_authorization_role_permission WHERE role_code = ?",
                String.class, roleCode));
        if (permissions.isEmpty()) {
            throw new IllegalStateException("Test platform role has no permissions: " + roleCode);
        }
        var identity = new ExecutionContext.Identity(actor, null, permissions, "PLATFORM",
                "business-platform-test-" + actor, roleCode);
        return ExecutionContext.open(identity, "business-platform-it");
    }

    /** 提供只用于此 PostgreSQL 测试的确定性扫描替身，不声称执行真实 ClamAV 扫描。
     */
    @TestConfiguration
    static class EvidenceScannerFixtures {
        /** 识别合成恶意和扫描器故障标记，并为实际读取字节计算摘要。
         * @return 集成测试专用扫描器
         */
        @Bean
        @Primary
        EvidenceContentScanner evidenceContentScanner() {
            return content -> {
                byte[] bytes = content.readAllBytes();
                String body = new String(bytes, StandardCharsets.ISO_8859_1);
                if (body.contains("SCANNER-UNAVAILABLE")) {
                    throw new IOException("Synthetic scanner failure");
                }
                String digest;
                try {
                    digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                } catch (java.security.NoSuchAlgorithmException exception) {
                    throw new IllegalStateException("SHA-256 is unavailable", exception);
                }
                return new EvidenceContentScanner.ScanResult(!body.contains("TEST-MALWARE"), digest);
            };
        }
    }
}
