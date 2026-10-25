package com.company.labor.foundation;

import com.company.labor.modules.demand.DemandDtos.UpsertRequest;
import com.company.labor.modules.demand.DemandService;
import com.company.labor.modules.demand.DemandStatus;
import com.company.labor.modules.identity.MembershipRole;
import com.company.labor.modules.identity.OrganizationDtos.CreateRequest;
import com.company.labor.modules.identity.OrganizationDtos.JoinRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipDecisionRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipRequestDecision;
import com.company.labor.modules.identity.OrganizationDtos.UpdateRequest;
import com.company.labor.modules.identity.OrganizationDtos.OrganizationReviewDecision;
import com.company.labor.modules.identity.OrganizationRole;
import com.company.labor.modules.identity.OrganizationService;
import com.company.labor.modules.identity.OrganizationStatus;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用独立 PostgreSQL 验证身份组织、资质审核、成员加入和需求发布的真实事务流程。 */
@SpringBootTest
class BusinessFoundationIT {
    /** 合成平台审核权限，不代表生产人员角色配置。 */
    private static final Set<String> ORGANIZATION_REVIEW = Set.of("platform:organizations:review");
    /** 合成需求审核权限，不代表生产人员角色配置。 */
    private static final Set<String> DEMAND_REVIEW = Set.of("platform:demands:review");
    /** 当前测试的组织和需求数据只使用合成标识。 */
    private static final long REVIEWER = 900L;

    /** 真实隔离测试数据库访问入口。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 首批组织业务服务。 */
    @Autowired
    private OrganizationService organizations;
    /** 首批需求业务服务。 */
    @Autowired
    private DemandService demands;

    /**
     * 复用现有集成测试对独立测试数据库及合成 JWT 密钥的校验。
     * @param registry 测试环境属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FoundationIT.properties(registry);
    }

    /** 清理仅属于本测试的业务表和审计行，不连接或清理开发数据库。 */
    @BeforeEach
    void reset() {
        ExecutionContext.clear();
        jdbc.execute("""
                TRUNCATE business_demand_delivery, business_review_decision, business_demand_revision,
                    business_demand, business_membership_request, business_membership,
                    business_organization_revision, business_organization,
                    foundation_idempotency, foundation_audit CASCADE
                """);
    }

    /** 验证组织审核、成员加入、需求审核及发布形成完整可追溯状态链。 */
    @Test
    void employerOrganizationAndDemandPassIndependentReviewGates() {
        long employerId = createOrganization(101L, OrganizationRole.EMPLOYER, "EMPLOYER-TEST-101", null);
        try (var ignored = identity(101L, employerId, Set.of("organization:organizations:write"))) {
            assertEquals(OrganizationStatus.PENDING_REVIEW, organizations.submit(employerId).status());
        }
        try (var ignored = identity(REVIEWER, null, ORGANIZATION_REVIEW)) {
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
        try (var ignored = identity(REVIEWER, null, DEMAND_REVIEW)) {
            assertEquals(DemandStatus.APPROVED_PENDING_PUBLISH,
                    demands.review(demandId,
                            new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                                    com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.APPROVE,
                                    null)).status());
        }
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
        try (var ignored = identity(REVIEWER, null, DEMAND_REVIEW)) {
            assertEquals(DemandStatus.NEEDS_CORRECTION,
                    demands.review(demandId, new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                            com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.NEEDS_CORRECTION,
                            "请补充供应商响应条件")).status());
        }
        try (var ignored = identity(505L, employerId, Set.of("organization:demands:write"))) {
            var corrected = demands.update(demandId, 1, demand());
            assertEquals(2, corrected.revision());
            assertEquals(DemandStatus.NEEDS_CORRECTION, corrected.status());
            demands.submit(demandId);
        }
        try (var ignored = identity(REVIEWER, null, DEMAND_REVIEW)) {
            assertEquals(DemandStatus.APPROVED_PENDING_PUBLISH,
                    demands.review(demandId, new com.company.labor.modules.demand.DemandDtos.ReviewRequest(
                            com.company.labor.modules.demand.DemandDtos.ReviewRequest.Decision.APPROVE, null)).status());
        }
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_review_decision
                WHERE resource_type = 'DEMAND' AND resource_id = ?
                """, Integer.class, demandId));
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

    /** 创建合成组织并让申请人持有初始管理员成员关系。 */
    private long createOrganization(long actor, OrganizationRole role, String registrationId, String qualification) {
        try (var ignored = identity(actor, null, Set.of("organization:create"))) {
            return organizations.create(new CreateRequest(role, "合成组织", registrationId, "AUTH-FIXTURE",
                    qualification)).id();
        }
    }

    /** 使用与申请人不同的合成平台审核身份处理组织结论。 */
    private void reviewOrganization(long organizationId, OrganizationReviewDecision decision, String reason) {
        try (var ignored = identity(REVIEWER, null, ORGANIZATION_REVIEW)) {
            organizations.review(organizationId,
                    new com.company.labor.modules.identity.OrganizationDtos.ReviewRequest(decision, reason));
        }
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
        return ExecutionContext.open(new ExecutionContext.Identity(actor, tenant, permissions), "business-it");
    }
}
