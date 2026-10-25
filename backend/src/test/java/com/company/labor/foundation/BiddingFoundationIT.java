package com.company.labor.foundation;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.modules.bidding.BiddingDtos.Allocation;
import com.company.labor.modules.bidding.BiddingDtos.AwardRequest;
import com.company.labor.modules.bidding.BiddingDtos.AwardStatus;
import com.company.labor.modules.bidding.BiddingDtos.BidStatus;
import com.company.labor.modules.bidding.BiddingDtos.ConfirmationRequest;
import com.company.labor.modules.bidding.BiddingDtos.SubmitRequest;
import com.company.labor.modules.bidding.BiddingService;
import com.company.labor.modules.demand.DemandDtos;
import com.company.labor.modules.demand.DemandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用独立 PostgreSQL 验证 BP-04 版本、配额、确认、隔离及 BP-05 快照交接边界。 */
@SpringBootTest
class BiddingFoundationIT {
    /** 用工方合成组织。 */
    private static final long EMPLOYER = 1001L;
    /** 供应商合成组织。 */
    private static final long SUPPLIER = 2001L;
    /** 第二家合成供应商。 */
    private static final long SECOND_SUPPLIER = 2002L;
    /** 合成需求。 */
    private static final long DEMAND = 3001L;
    /** 用工方创建人，不得代替独立选标人。 */
    private static final long CREATOR = 11L;
    /** 用工方选标人。 */
    private static final long SELECTOR = 12L;
    /** 供应商应标人。 */
    private static final long BIDDER = 21L;
    /** 第二家供应商应标人。 */
    private static final long SECOND_BIDDER = 22L;
    /** 用工方需求编辑人。 */
    private static final long EDITOR = 13L;
    /** 用工方需求发布人。 */
    private static final long PUBLISHER = 14L;

    /** 真实测试数据库访问入口。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** BP-04 服务。 */
    @Autowired
    private BiddingService bidding;
    /** BP-03 生命周期服务。 */
    @Autowired
    private DemandService demands;

    /** 复用独立 PostgreSQL 测试数据库配置。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FoundationIT.properties(registry);
    }

    /** 清空 BP-04 业务事实并构造一个开放需求和两个交易组织。 */
    @BeforeEach
    void reset() {
        ExecutionContext.clear();
        jdbc.execute("""
                TRUNCATE business_bp05_snapshot, business_bid_supervision_event,
                    business_bid_award, business_bid_revision, business_bid,
                    business_demand_delivery, business_review_decision, business_demand_revision,
                    business_demand, business_membership_request, business_membership,
                    business_organization_revision, business_organization,
                    foundation_idempotency, foundation_audit CASCADE
                """);
        jdbc.update("""
                INSERT INTO business_organization
                    (id, tenant_id, organization_role, legal_name, registration_id,
                     authorization_reference, status, created_by)
                VALUES (?, ?, 'EMPLOYER', '合成用工方', 'BP04-EMPLOYER', 'AUTH', 'APPROVED', ?),
                       (?, ?, 'LABOR_SUPPLIER', '合成供应商', 'BP04-SUPPLIER', 'AUTH', 'APPROVED', ?),
                       (?, ?, 'LABOR_SUPPLIER', '合成供应商二', 'BP04-SUPPLIER-2', 'AUTH', 'APPROVED', ?)
                """, EMPLOYER, EMPLOYER, CREATOR, SUPPLIER, SUPPLIER, BIDDER,
                SECOND_SUPPLIER, SECOND_SUPPLIER, SECOND_BIDDER);
        jdbc.update("""
                INSERT INTO business_membership
                    (organization_id, tenant_id, actor_id, member_role, status, created_by)
                VALUES (?, ?, ?, 'DEMAND_SELECTOR', 'ACTIVE', ?),
                       (?, ?, ?, 'SUPPLIER_BIDDER', 'ACTIVE', ?),
                       (?, ?, ?, 'SUPPLIER_BIDDER', 'ACTIVE', ?),
                       (?, ?, ?, 'DEMAND_EDITOR', 'ACTIVE', ?),
                       (?, ?, ?, 'DEMAND_PUBLISHER', 'ACTIVE', ?)
                """, EMPLOYER, EMPLOYER, SELECTOR, CREATOR, SUPPLIER, SUPPLIER, BIDDER, BIDDER,
                SECOND_SUPPLIER, SECOND_SUPPLIER, SECOND_BIDDER, SECOND_BIDDER,
                EMPLOYER, EMPLOYER, EDITOR, CREATOR, EMPLOYER, EMPLOYER, PUBLISHER, CREATOR);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO business_demand
                    (id, organization_id, tenant_id, status, current_revision, created_by)
                VALUES (?, ?, ?, 'OPEN_FOR_BID', 1, ?)
                """, DEMAND, EMPLOYER, EMPLOYER, CREATOR);
        jdbc.update("""
                INSERT INTO business_demand_revision
                    (demand_id, tenant_id, revision, related_party_relation, service_scope, location,
                     starts_at, ends_at, title, quantity, quantity_unit, target, pricing_mode,
                     measurement_basis, settlement_cycle, bid_deadline, confirmation_deadline,
                     selection_rule, created_by)
                VALUES (?, ?, 1, '合成关系', '合成范围', '合成地点', ?, ?, 'BP04需求', 10,
                        '人天', '合成目标', 'OPEN_QUOTE', '按确认口径', '月结', ?, ?, '人工比较', ?)
                """, DEMAND, EMPLOYER, Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(86_400)), Timestamp.from(now.plusSeconds(600)),
                Timestamp.from(now.plusSeconds(7200)), CREATOR);
    }

    /** 同一供应商的修改追加不可变版本，暂停时只允许撤回。 */
    @Test
    void bidRevisionIsAppendOnlyAndSuspensionOnlyAllowsWithdrawal() {
        var first = submit("bid-version-1", "100");
        var second = submit("bid-version-2", "120");
        assertEquals(2, second.revision());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_bid_revision WHERE bid_id = ?", Integer.class, first.id()));

        jdbc.update("UPDATE business_demand SET status = 'SUSPENDED' WHERE id = ?", DEMAND);
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(BidStatus.WITHDRAWN, bidding.withdraw(first.id()).status());
            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> bidding.submit(DEMAND, "bid-version-3", submitRequest("130")));
            assertEquals(PlatformError.DATA_CONFLICT.code(), rejected.getCode());
        }
    }

    /** 截止前撤回后重提沿用同一应标并追加下一不可变版本。 */
    @Test
    void withdrawalAndResubmissionAppendToOneBidHistory() {
        var first = submit("bid-withdraw-resubmit-1", "100");
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            bidding.withdraw(first.id());
        }
        var resubmitted = submit("bid-withdraw-resubmit-2", "125");
        assertEquals(first.id(), resubmitted.id());
        assertEquals(2, resubmitted.revision());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_bid_revision WHERE bid_id = ?", Integer.class, first.id()));
    }

    /** 需求截止后用工方独立选标，供应商确认后只生成一次 BP-05 快照。 */
    @Test
    void awardUsesDemandConfirmationWindowAndCreatesOneSnapshot() {
        var bid = submit("bid-award-1", "100");
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE business_demand_revision
                SET bid_deadline = ?, confirmation_deadline = ?
                WHERE demand_id = ? AND revision = 1
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)), DEMAND);
        try (var ignored = identity(SELECTOR, EMPLOYER,
                Set.of("organization:bids:select"))) {
            var awards = bidding.award(DEMAND,
                    new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                            "ABSOLUTE", "NONE"));
            assertEquals(AwardStatus.PENDING_CONFIRMATION, awards.get(0).status());
            assertEquals("BP04需求", awards.get(0).terms().demandTitle());
        }
        try (var ignored = identity(BIDDER, SUPPLIER,
                Set.of("organization:bids:write"))) {
            var confirmed = bidding.confirm(awardsId(), new ConfirmationRequest(true, null));
            assertEquals(AwardStatus.CONFIRMED, confirmed.status());
            bidding.confirm(awardsId(), new ConfirmationRequest(true, null));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_bp05_snapshot WHERE demand_id = ?", Integer.class, DEMAND));
        assertEquals(4, jdbc.queryForObject(
                "SELECT awarded_quantity FROM business_bp05_snapshot WHERE demand_id = ?", BigDecimal.class, DEMAND)
                .intValue());
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertTrue(bidding.snapshots(DEMAND).get(0).snapshotPayload().contains("serviceScope"));
        }
    }

    /** 多家供应商的确认与超时独立处理，每家只按自身结果交接快照。 */
    @Test
    void multipleSuppliersConfirmIndependentlyAndReleaseOnlyExpiredAward() {
        var firstBid = submit("bid-multi-award-1", "100");
        var secondBid = submitAs(SECOND_BIDDER, SECOND_SUPPLIER, "bid-multi-award-2", "120");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);

        List<com.company.labor.modules.bidding.BiddingDtos.AwardView> awards;
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            awards = bidding.award(DEMAND, new AwardRequest(List.of(
                    new Allocation(firstBid.id(), new BigDecimal("3")),
                    new Allocation(secondBid.id(), new BigDecimal("4"))), "ABSOLUTE", "NONE"));
        }
        var firstAward = awards.stream().filter(award -> award.supplierOrganizationId() == SUPPLIER)
                .findFirst().orElseThrow();
        var secondAward = awards.stream().filter(award -> award.supplierOrganizationId() == SECOND_SUPPLIER)
                .findFirst().orElseThrow();
        assertEquals(AwardStatus.PENDING_CONFIRMATION, firstAward.status());
        assertEquals(AwardStatus.PENDING_CONFIRMATION, secondAward.status());

        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(AwardStatus.CONFIRMED,
                    bidding.confirm(firstAward.id(), new ConfirmationRequest(true, null)).status());
            bidding.confirm(firstAward.id(), new ConfirmationRequest(true, null));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_bp05_snapshot WHERE demand_id = ?", Integer.class, DEMAND));
        try (var ignored = identity(SECOND_BIDDER, SECOND_SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(AwardStatus.PENDING_CONFIRMATION, bidding.listAwards(DEMAND).get(0).status());
        }

        jdbc.update("UPDATE business_bid_award SET confirmation_deadline = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), secondAward.id());
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            assertEquals(1, bidding.expire(DEMAND));
            assertEquals(0, bidding.expire(DEMAND));
            var snapshots = bidding.snapshots(DEMAND);
            assertEquals(1, snapshots.size());
            assertEquals(firstAward.id(), snapshots.get(0).awardId());
        }
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM business_bid_award WHERE id = ?", String.class, secondAward.id()));
    }

    /** 供应商应标数据按租户隔离，另一组织不能读取或确认授予。 */
    @Test
    void supplierCannotReadAnotherTenantBid() {
        var bid = submit("bid-isolation-1", "100");
        try (var ignored = identity(99L, 9999L, Set.of("organization:bids:write"))) {
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class, () -> bidding.listOwn()).getCode());
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.withdraw(bid.id())).getCode());
        }
    }

    /** 超时确认以已提交的 EXPIRED 结果释放配额，不因异常回滚。 */
    @Test
    void confirmationTimeoutReleasesWithoutRollback() {
        var bid = submit("bid-expire-1", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        long awardId;
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            bidding.award(DEMAND, new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                    "ABSOLUTE", "NONE"));
            awardId = awardsId();
        }
        jdbc.update("UPDATE business_bid_award SET confirmation_deadline = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), awardId);
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(AwardStatus.EXPIRED,
                    bidding.confirm(awardId, new ConfirmationRequest(true, null)).status());
        }
        assertEquals("CONFIRMATION_TIMEOUT", jdbc.queryForObject(
                "SELECT decision_reason FROM business_bid_award WHERE id = ?", String.class, awardId));
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            var renewedAwards = bidding.award(DEMAND,
                    new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                            "ABSOLUTE", "NONE"));
            assertEquals(AwardStatus.PENDING_CONFIRMATION,
                    renewedAwards.stream().filter(award -> award.id() != awardId)
                            .findFirst().orElseThrow().status());
        }
    }

    /** 需求暂停后禁止供应商确认待确认授予。 */
    @Test
    void suspendedDemandBlocksPendingConfirmation() {
        var bid = submit("bid-suspend-confirm", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            bidding.award(DEMAND, new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                    "ABSOLUTE", "NONE"));
        }
        jdbc.update("UPDATE business_demand SET status = 'SUSPENDED' WHERE id = ?", DEMAND);
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(PlatformError.DATA_CONFLICT.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.confirm(awardsId(), new ConfirmationRequest(true, null))).getCode());
        }
    }

    /** 拒绝理由写入授予决定，供应商状态可重复读取。 */
    @Test
    void rejectionReasonIsRecorded() {
        var bid = submit("bid-reject-reason", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            bidding.award(DEMAND, new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                    "ABSOLUTE", "NONE"));
        }
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(AwardStatus.REJECTED,
                    bidding.confirm(awardsId(), new ConfirmationRequest(false, "承诺条件无法满足")).status());
        }
        assertEquals("承诺条件无法满足", jdbc.queryForObject(
                "SELECT decision_reason FROM business_bid_award WHERE id = ?", String.class, awardsId()));
    }

    /** 比例授予在需求未承载取整和余数合同前被拒绝，不把比例误当绝对数量。 */
    @Test
    void proportionalAllocationRequiresExplicitContract() {
        var bid = submit("bid-proportional", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> bidding.award(DEMAND, new AwardRequest(
                            List.of(new Allocation(bid.id(), new BigDecimal("40"))), "PROPORTIONAL", "DOWN")));
            assertEquals(PlatformError.INVALID_ARGUMENT.code(), rejected.getCode());
        }
    }

    /** 两个选标事务竞争同一需求时只能有一个不超配结果。 */
    @Test
    void concurrentAwardsCannotOverAllocateDemand() throws Exception {
        var first = submit("bid-concurrent-1", "100");
        var second = submitAs(SECOND_BIDDER, SECOND_SUPPLIER, "bid-concurrent-2", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = executor.submit(() -> awardFromLatch(start, first.id(), "6"));
            Future<Boolean> two = executor.submit(() -> awardFromLatch(start, second.id(), "6"));
            start.countDown();
            assertEquals(1, List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)).stream()
                    .filter(Boolean.TRUE::equals).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(6, jdbc.queryForObject("SELECT COALESCE(SUM(awarded_quantity), 0) FROM business_bid_award",
                BigDecimal.class).intValue());
    }

    /** 组织被暂停后，供应商成员即使保留数据库角色也不能继续应标。 */
    @Test
    void suspendedSupplierOrganizationCannotBid() {
        jdbc.update("UPDATE business_organization SET status = 'SUSPENDED' WHERE id = ?", SUPPLIER);
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(PlatformError.ACCESS_DENIED.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.submit(DEMAND, "bid-suspended-org", submitRequest("100"))).getCode());
        }
    }

    /** 选标角色独立于需求编辑角色，编辑成员不能凭令牌权限选标。 */
    @Test
    void demandEditorCannotSelectWithoutSelectorMembership() {
        var bid = submit("bid-editor-cannot-select", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        try (var ignored = identity(EDITOR, EMPLOYER, Set.of("organization:bids:select"))) {
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> bidding.award(DEMAND, new AwardRequest(
                            List.of(new Allocation(bid.id(), new BigDecimal("4"))), "ABSOLUTE", "NONE")))
                    .getCode());
        }
    }

    /** 已准入状态失效后，用工方不能继续分配供应商配额。 */
    @Test
    void suspendedEmployerOrganizationCannotSelect() {
        var bid = submit("bid-employer-suspended", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        jdbc.update("UPDATE business_organization SET status = 'SUSPENDED' WHERE id = ?", EMPLOYER);
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> bidding.award(DEMAND, new AwardRequest(
                            List.of(new Allocation(bid.id(), new BigDecimal("4"))), "ABSOLUTE", "NONE")))
                    .getCode());
        }
    }

    /** 供应商无法查看其他供应商的报价和应标列表。 */
    @Test
    void supplierCannotReadCompetitorBids() {
        submit("bid-private-1", "100");
        submitAs(SECOND_BIDDER, SECOND_SUPPLIER, "bid-private-2", "125");
        try (var ignored = identity(SECOND_BIDDER, SECOND_SUPPLIER,
                Set.of("organization:bids:write", "organization:bids:select"))) {
            assertEquals(1, bidding.listOwn().size());
            assertEquals(PlatformError.ACCESS_DENIED.code(), assertThrows(BusinessException.class,
                    () -> bidding.listForDemand(DEMAND)).getCode());
        }
    }

    /** 需求改版会重新进入审核，并关闭旧版本应标。 */
    @Test
    void publishedDemandRevisionClosesOldBids() {
        var bid = submit("bid-old-revision", "100");
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        long pendingAward;
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            bidding.award(DEMAND, new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                    "ABSOLUTE", "NONE"));
            pendingAward = awardsId();
        }
        try (var ignored = identity(EDITOR, EMPLOYER, Set.of("organization:demands:write"))) {
            var revised = demands.update(DEMAND, 1, new DemandDtos.UpsertRequest(
                    "合成关系", "新的范围", "新的地点", Instant.now().plusSeconds(3600),
                    Instant.now().plusSeconds(86_400), "新版需求", new BigDecimal("10"), "人天",
                    "新目标", "OPEN_QUOTE", "按确认口径", "月结",
                    Instant.now().plusSeconds(600), Instant.now().plusSeconds(7200), "人工比较"));
            assertEquals("PENDING_REVIEW", revised.status().name());
        }
        assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM business_bid WHERE id = ?", String.class, bid.id()));
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM business_bid_award WHERE id = ?", String.class, pendingAward));
        try (var ignored = identity(40, null, Set.of("platform:demands:review"))) {
            demands.review(DEMAND, new DemandDtos.ReviewRequest(DemandDtos.ReviewRequest.Decision.APPROVE, null));
        }
        try (var ignored = identity(PUBLISHER, EMPLOYER, Set.of("organization:demands:publish"))) {
            demands.publish(DEMAND);
        }
        var newBid = submit("bid-new-revision", "125");
        assertNotEquals(bid.id(), newBid.id());
        assertEquals(1, newBid.revision());
        jdbc.update("UPDATE business_demand_revision SET bid_deadline = ? WHERE demand_id = ? AND revision = 2",
                Timestamp.from(Instant.now().minusSeconds(60)), DEMAND);
        try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
            assertEquals(PlatformError.DATA_CONFLICT.code(),
                    assertThrows(BusinessException.class, () -> bidding.award(DEMAND,
                            new AwardRequest(List.of(new Allocation(bid.id(), new BigDecimal("4"))),
                                    "ABSOLUTE", "NONE"))).getCode());
        }
        try (var ignored = identity(BIDDER, SUPPLIER, Set.of("organization:bids:write"))) {
            assertEquals(PlatformError.DATA_CONFLICT.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.confirm(pendingAward, new ConfirmationRequest(true, null))).getCode());
        }
    }

    /** 需求创建人不得以平台监督员身份记录关联监督证据。 */
    @Test
    void supervisionEnforcesConflictRecusal() {
        submit("bid-supervision-conflict", "100");
        try (var ignored = identity(CREATOR, null, Set.of("platform:bids:supervise"))) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.supervise(DEMAND, "CHECK", "evidence-1")).getCode());
        }
        try (var ignored = identity(BIDDER, null, Set.of("platform:bids:supervise"))) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.supervise(DEMAND, "CHECK", "evidence-2")).getCode());
        }
        jdbc.update("""
                INSERT INTO business_review_decision
                    (tenant_id, resource_type, resource_id, resource_version, from_status, decision,
                     to_status, reviewer_id, reason)
                VALUES (?, 'DEMAND', ?, 1, 'PENDING_REVIEW', 'APPROVE', 'APPROVED_PENDING_PUBLISH', 35, NULL)
                """, EMPLOYER, DEMAND);
        try (var ignored = identity(35, null, Set.of("platform:bids:supervise"))) {
            assertEquals(PlatformError.INDEPENDENT_REVIEW_REQUIRED.code(),
                    assertThrows(BusinessException.class,
                            () -> bidding.supervise(DEMAND, "CHECK", "evidence-3")).getCode());
        }
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_bid_supervision_event
                WHERE outcome = 'RECUSAL'
                """, Integer.class));
        try (var ignored = identity(36, null, Set.of("platform:bids:supervise"))) {
            bidding.supervise(DEMAND, "CHECK", "evidence-4");
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM business_bid_supervision_event
                WHERE outcome = 'RECORDED'
                """, Integer.class));
    }

    /** 提交一份供应商应标版本。 */
    private com.company.labor.modules.bidding.BiddingDtos.BidView submit(String key, String amount) {
        return submitAs(BIDDER, SUPPLIER, key, amount);
    }

    /** 以指定供应商身份提交一份应标版本。 */
    private com.company.labor.modules.bidding.BiddingDtos.BidView submitAs(long actor, long tenant,
                                                                            String key, String amount) {
        try (var ignored = identity(actor, tenant, Set.of("organization:bids:write"))) {
            return bidding.submit(DEMAND, key, submitRequest(amount));
        }
    }

    /** 在线程事务中执行并发选标，返回是否成功。 */
    private boolean awardFromLatch(CountDownLatch start, long bidId, String quantity) {
        try {
            start.await(10, TimeUnit.SECONDS);
            try (var ignored = identity(SELECTOR, EMPLOYER, Set.of("organization:bids:select"))) {
                bidding.award(DEMAND, new AwardRequest(List.of(new Allocation(bidId, new BigDecimal(quantity))),
                        "ABSOLUTE", "NONE"));
                return true;
            }
        } catch (BusinessException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 查询当前需求唯一授予。 */
    private long awardsId() {
        return jdbc.queryForObject("SELECT id FROM business_bid_award WHERE demand_id = ?", Long.class, DEMAND);
    }

    /** 构造结构化应标请求。 */
    private SubmitRequest submitRequest(String amount) {
        return new SubmitRequest(new BigDecimal("10"), "可按期交付", "无", "合成方案",
                new BigDecimal(amount), "CNY", "人天", new BigDecimal("6"), "30日",
                "按月对公付款", true);
    }

    /** 建立测试身份作用域，调用方负责关闭。 */
    private ExecutionContext.Scope identity(long actor, Long tenant, Set<String> permissions) {
        return ExecutionContext.open(new ExecutionContext.Identity(actor, tenant, permissions), "bidding-it");
    }
}
