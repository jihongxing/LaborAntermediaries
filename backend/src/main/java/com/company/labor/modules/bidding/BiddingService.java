package com.company.labor.modules.bidding;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.foundation.reliability.IdempotentExecutor;
import com.company.labor.modules.bidding.BiddingDtos.AwardRequest;
import com.company.labor.modules.bidding.BiddingDtos.AwardStatus;
import com.company.labor.modules.bidding.BiddingDtos.AwardView;
import com.company.labor.modules.bidding.BiddingDtos.BidStatus;
import com.company.labor.modules.bidding.BiddingDtos.BidView;
import com.company.labor.modules.bidding.BiddingDtos.ConfirmationRequest;
import com.company.labor.modules.bidding.BiddingDtos.ConfirmationTerms;
import com.company.labor.modules.bidding.BiddingDtos.SnapshotView;
import com.company.labor.modules.bidding.BiddingDtos.SubmitRequest;
import com.company.labor.modules.identity.MembershipRole;
import com.company.labor.modules.identity.OrganizationAuthorization;
import com.company.labor.modules.identity.OrganizationRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 实现 BP-04 应标、选标、供应商确认及 BP-05 快照交接，不创建履约或资金对象。 */
@Service
public class BiddingService {
    /** 授予读取及确认条件视图的统一查询，按应标绑定版本联查，不读取后续需求版本。 */
    private static final String AWARD_SELECT = """
            SELECT a.id, a.demand_id, a.employer_tenant_id, a.bid_id, a.supplier_tenant_id, a.bid_revision,
                   a.awarded_quantity, a.confirmation_deadline, a.status, a.decided_at, a.confirmed_at,
                   b.demand_revision, dr.title AS demand_title, dr.service_scope AS demand_service_scope,
                   dr.location AS demand_location, dr.starts_at, dr.ends_at, dr.quantity AS demand_quantity,
                   dr.quantity_unit, dr.target, dr.pricing_mode, dr.measurement_basis, dr.settlement_cycle,
                   dr.bid_deadline, dr.confirmation_deadline AS demand_confirmation_deadline,
                   dr.selection_rule, br.committed_quantity, br.commitment, br.deviation_items, br.proposal,
                   br.quoted_amount, br.quote_currency, br.quote_unit, br.tax_rate, br.payment_term,
                   br.payment_arrangement
            FROM business_bid_award a
            JOIN business_bid b ON b.id = a.bid_id AND b.tenant_id = a.supplier_tenant_id
            JOIN business_demand_revision dr
              ON dr.demand_id = a.demand_id AND dr.revision = b.demand_revision
            JOIN business_bid_revision br
              ON br.bid_id = a.bid_id AND br.tenant_id = a.supplier_tenant_id AND br.revision = a.bid_revision
            """;

    /** 应标、授予和交接数据访问入口。 */
    private final JdbcTemplate jdbc;
    /** 业务标识生成服务。 */
    private final SequenceIds ids;
    /** 组织成员和组织状态授权服务。 */
    private final OrganizationAuthorization authorization;
    /** 标识化审计入口。 */
    private final AuditRecorder audit;
    /** 应标提交的事务幂等执行器。 */
    private final IdempotentExecutor idempotency;
    /** 独立提交监督事实和审计记录的事务模板。 */
    private final TransactionTemplate supervisionTransaction;

    /** 创建 BP-04 业务服务。 */
    public BiddingService(JdbcTemplate jdbc, SequenceIds ids, OrganizationAuthorization authorization,
                          AuditRecorder audit, IdempotentExecutor idempotency,
                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.authorization = authorization;
        this.audit = audit;
        this.idempotency = idempotency;
        supervisionTransaction = new TransactionTemplate(transactionManager);
        supervisionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 在截止前创建应标或追加一个不可变应标版本。
     * 先锁需求行再检查窗口，保证截止边界与并发提交使用同一数据库时钟顺序。
     * @param demandId 需求标识
     * @param idempotencyKey 客户端幂等键
     * @param request 应标版本内容
     * @return 当前应标视图
     * @throws BusinessException 供应商、需求状态、截止窗口或组织隔离不满足
     */
    @Transactional
    public BidView submit(long demandId, String idempotencyKey, SubmitRequest request) {
        long supplierId = requireSupplierBidder();
        DemandRow demand = lockDemand(demandId);
        ensureBidWindowOpen(demand, databaseNow());
        validateSubmit(request);
        return idempotency.execute("business:bid:submit:v1", idempotencyKey,
                Map.of("demandId", demandId, "request", request), BidView.class,
                () -> submitOnce(demand, supplierId, request));
    }

    /** 在已锁定的需求行中创建或追加应标版本。 */
    private BidView submitOnce(DemandRow demand, long supplierId, SubmitRequest request) {
        BidRow current = findReusableBid(demand.id(), demand.revision(), supplierId, true);
        long bidId;
        long revision;
        long actor = ExecutionContext.require().actorId();
        if (current == null) {
            bidId = ids.nextId();
            revision = 1;
            jdbc.update("""
                    INSERT INTO business_bid
                        (id, demand_id, demand_revision, employer_tenant_id, supplier_tenant_id, tenant_id,
                         current_revision, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?)
                    """, bidId, demand.id(), demand.revision(), demand.employerTenantId(), supplierId, supplierId, actor);
        } else {
            bidId = current.id();
            revision = current.revision() + 1;
        }
        jdbc.update("""
                INSERT INTO business_bid_revision
                    (bid_id, tenant_id, revision, committed_quantity, commitment, deviation_items,
                     proposal, quoted_amount, quote_currency, quote_unit, tax_rate, payment_term,
                     payment_arrangement, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bidId, supplierId, revision, request.committedQuantity(), request.commitment().trim(),
                request.deviationItems().trim(), request.proposal().trim(), request.quotedAmount(),
                request.quoteCurrency().trim().toUpperCase(), request.quoteUnit().trim(), request.taxRate(),
                request.paymentTerm().trim(), request.paymentArrangement().trim(), actor);
        if (current != null) {
            ensureUpdated(jdbc.update("""
                    UPDATE business_bid SET current_revision = ?, status = 'ACTIVE', withdrawn_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND tenant_id = ? AND status IN ('ACTIVE', 'WITHDRAWN')
                        AND current_revision = ?
                    """, revision, bidId, supplierId, current.revision()));
        }
        audit.record("bid:submit", "bid", Long.toString(bidId), "SUCCESS");
        return findBid(bidId, supplierId).view();
    }

    /**
     * 撤回应标；需求暂停期间允许撤回，但不允许修改、重提、选标或确认。
     * @param bidId 应标标识
     * @return 撤回后的应标视图
     * @throws BusinessException 当前成员、需求状态或截止门禁不允许
     */
    @Transactional
    public BidView withdraw(long bidId) {
        long supplierId = requireSupplierBidder();
        BidRow bid = findBid(bidId, supplierId);
        DemandRow demand = lockDemand(bid.demandId());
        if (demand.status() == DemandState.CLOSED || bid.status() == BidStatus.CLOSED) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (bid.status() == BidStatus.WITHDRAWN && demand.status() != DemandState.CLOSED) {
            return bid.view();
        }
        if (demand.status() == DemandState.SUSPENDED && bid.status() == BidStatus.ACTIVE) {
            ensureUpdated(jdbc.update("""
                    UPDATE business_bid SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                    """, bidId, supplierId));
            audit.record("bid:withdraw", "bid", Long.toString(bidId), "SUCCESS");
            return findBid(bidId, supplierId).view();
        }
        if (demand.status() != DemandState.OPEN_FOR_BID) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (!demand.bidDeadline().isAfter(databaseNow())) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        ensureUpdated(jdbc.update("""
                UPDATE business_bid SET status = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                """, bidId, supplierId));
        audit.record("bid:withdraw", "bid", Long.toString(bidId), "SUCCESS");
        return findBid(bidId, supplierId).view();
    }

    /**
     * 查询当前供应商自己的应标，永远按供应商租户过滤。
     * @return 当前供应商应标列表
     * @throws BusinessException 当前身份不是有效供应商成员
     */
    public List<BidView> listOwn() {
        long supplierId = requireSupplierBidder();
        return jdbc.query("""
                SELECT b.id, b.demand_id, b.demand_revision, b.employer_tenant_id,
                       b.supplier_tenant_id, b.current_revision, b.status,
                       r.committed_quantity, r.commitment, r.deviation_items, r.proposal,
                       r.quoted_amount, r.quote_currency, r.quote_unit, r.tax_rate,
                       r.payment_term, r.payment_arrangement, r.created_at
                FROM business_bid b
                JOIN business_bid_revision r ON r.bid_id = b.id AND r.tenant_id = b.tenant_id
                    AND r.revision = b.current_revision
                WHERE b.tenant_id = ?
                ORDER BY b.updated_at DESC, b.id DESC
                LIMIT 200
                """, (result, row) -> mapBid(result).view(), supplierId);
    }

    /**
     * 查询需求收到的应标；只有用工方选标成员可见，供应商之间不会互相泄露报价。
     * @param demandId 需求标识
     * @return 需求应标列表
     * @throws BusinessException 不是需求所属用工方的选标成员
     */
    public List<BidView> listForDemand(long demandId) {
        DemandRow demand = locklessDemand(demandId);
        requireSelector(demand.employerTenantId());
        return jdbc.query("""
                SELECT b.id, b.demand_id, b.demand_revision, b.employer_tenant_id,
                       b.supplier_tenant_id, b.current_revision, b.status,
                       r.committed_quantity, r.commitment, r.deviation_items, r.proposal,
                       r.quoted_amount, r.quote_currency, r.quote_unit, r.tax_rate,
                       r.payment_term, r.payment_arrangement, r.created_at
                FROM business_bid b
                JOIN business_bid_revision r ON r.bid_id = b.id AND r.tenant_id = b.tenant_id
                    AND r.revision = b.current_revision
                WHERE b.demand_id = ? AND b.employer_tenant_id = ?
                ORDER BY b.created_at, b.id
                LIMIT 200
                """, (result, row) -> mapBid(result).view(), demandId, demand.employerTenantId());
    }

    /**
     * 在截止后一次性授予多个供应商配额。
     * 需求行锁将总量检查与插入串行化，防止两个选标事务共同超配；当前只接受明确的绝对数量配额。
     * @param demandId 需求标识
     * @param request 配额和确认期限
     * @return 新建授予列表
     * @throws BusinessException 权限、截止、配额、版本或利益冲突不满足
     */
    @Transactional
    public List<AwardView> award(long demandId, AwardRequest request) {
        DemandRow demand = lockDemand(demandId);
        requireSelector(demand.employerTenantId());
        if (request == null || request.allocations() == null || request.allocations().isEmpty()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        long actor = ExecutionContext.require().actorId();
        if (actor == demand.createdBy()) {
            audit.record("bid:award", "demand", Long.toString(demandId), "DENIED");
            throw new BusinessException(PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
        Instant now = databaseNow();
        if (demand.status() != DemandState.OPEN_FOR_BID || demand.bidDeadline().isAfter(now)) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (demand.confirmationDeadline() == null || !demand.confirmationDeadline().isAfter(now)
                || !"ABSOLUTE".equalsIgnoreCase(request.allocationMode())
                || (request.roundingMode() != null && !"NONE".equalsIgnoreCase(request.roundingMode()))) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (request.allocations().stream().map(BiddingDtos.Allocation::bidId).distinct().count()
                != request.allocations().size()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        expirePendingForDemand(demandId);
        BigDecimal existing = jdbc.queryForObject("""
                SELECT COALESCE(SUM(awarded_quantity), 0) FROM business_bid_award
                WHERE demand_id = ? AND status IN ('PENDING_CONFIRMATION', 'CONFIRMED')
                """, BigDecimal.class, demandId);
        BigDecimal requested = request.allocations().stream().map(BiddingDtos.Allocation::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (existing.add(requested).compareTo(demand.quantity()) > 0) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        for (BiddingDtos.Allocation allocation : request.allocations()) {
            BidRow bid = findBidForDemand(allocation.bidId(), demandId, true);
            if (bid.status() != BidStatus.ACTIVE || bid.demandRevision() == null
                    || bid.demandRevision() != demand.revision()
                    || allocation.quantity().compareTo(bid.committedQuantity()) > 0) {
                throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            int inserted = jdbc.update("""
                    INSERT INTO business_bid_award
                        (id, demand_id, employer_tenant_id, bid_id, supplier_tenant_id, bid_revision,
                         awarded_quantity, confirmation_deadline, status, decided_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_CONFIRMATION', ?)
                    ON CONFLICT DO NOTHING
                    """, ids.nextId(), demandId, demand.employerTenantId(), bid.id(), bid.supplierTenantId(),
                    bid.revision(), allocation.quantity(), Timestamp.from(demand.confirmationDeadline()), actor);
            if (inserted != 1) {
                throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            audit.record("bid:award", "award", Long.toString(allocation.bidId()), "SUCCESS");
        }
        return listAwardsForDemand(demandId, demand.employerTenantId());
    }

    /**
     * 供应商对授予作全量确认或拒绝，确认成功后同一事务创建不可变 BP-05 快照。
     * @param awardId 授予标识
     * @param request 明确决定
     * @return 授予状态
     * @throws BusinessException 授予不属于当前供应商、已过期或决定冲突
     */
    @Transactional
    public AwardView confirm(long awardId, ConfirmationRequest request) {
        long supplierId = requireSupplierBidder();
        if (request == null || request.accepted() == null
                || (!request.accepted() && (request.reason() == null || request.reason().isBlank()))) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        AwardRow initial = findAward(awardId, null, supplierId, false);
        DemandRow demand = lockDemand(initial.demandId());
        AwardRow award = findAward(awardId, null, supplierId, true);
        if (award.status() == AwardStatus.CONFIRMED) {
            if (!request.accepted()) {
                throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            return award.view();
        }
        if (demand.status() != DemandState.OPEN_FOR_BID
                || award.terms().demandRevision() != demand.revision()) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (award.status() == AwardStatus.REJECTED || award.status() == AwardStatus.EXPIRED) {
            if (request.accepted()) {
                throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            return award.view();
        }
        if (!award.confirmationDeadline().isAfter(databaseNow())) {
            ensureUpdated(jdbc.update("""
                    UPDATE business_bid_award SET status = 'EXPIRED', decision_reason = 'CONFIRMATION_TIMEOUT'
                    WHERE id = ? AND supplier_tenant_id = ? AND status = 'PENDING_CONFIRMATION'
                    """, awardId, supplierId));
            audit.record("bid:expire", "award", Long.toString(awardId), "SUCCESS");
            return findAward(awardId, null, supplierId, false).view();
        }
        if (request.accepted()) {
            ensureUpdated(jdbc.update("""
                    UPDATE business_bid_award SET status = 'CONFIRMED', decision_reason = ?,
                        confirmed_at = clock_timestamp()
                    WHERE id = ? AND supplier_tenant_id = ? AND status = 'PENDING_CONFIRMATION'
                    """, normalizedReason(request.reason()), awardId, supplierId));
            int snapshotCreated = jdbc.update("""
                    INSERT INTO business_bp05_snapshot
                        (award_id, demand_id, demand_revision, employer_tenant_id, supplier_tenant_id, bid_id,
                         bid_revision, awarded_quantity, snapshot_status, snapshot_payload, confirmed_by, confirmed_at)
                    SELECT a.id, a.demand_id, b.demand_revision, a.employer_tenant_id, a.supplier_tenant_id,
                           a.bid_id, a.bid_revision, a.awarded_quantity, 'READY',
                           jsonb_build_object(
                               'demand', jsonb_build_object('revision', b.demand_revision, 'title', dr.title,
                                   'serviceScope', dr.service_scope, 'location', dr.location,
                                   'startsAt', dr.starts_at, 'endsAt', dr.ends_at, 'quantity', dr.quantity,
                                   'quantityUnit', dr.quantity_unit, 'target', dr.target,
                                   'pricingMode', dr.pricing_mode, 'measurementBasis', dr.measurement_basis,
                                   'settlementCycle', dr.settlement_cycle, 'bidDeadline', dr.bid_deadline,
                                   'confirmationDeadline', dr.confirmation_deadline,
                                   'selectionRule', dr.selection_rule),
                               'bid', jsonb_build_object('revision', a.bid_revision,
                                   'committedQuantity', r.committed_quantity, 'commitment', r.commitment,
                                   'deviationItems', r.deviation_items, 'proposal', r.proposal,
                                   'quotedAmount', r.quoted_amount, 'quoteCurrency', r.quote_currency,
                                   'quoteUnit', r.quote_unit, 'taxRate', r.tax_rate,
                                   'paymentTerm', r.payment_term, 'paymentArrangement', r.payment_arrangement),
                               'award', jsonb_build_object('quantity', a.awarded_quantity,
                                   'confirmationDeadline', a.confirmation_deadline,
                                   'decidedAt', a.decided_at)), ?, a.confirmed_at
                    FROM business_bid_award a
                    JOIN business_demand d ON d.id = a.demand_id AND d.tenant_id = a.employer_tenant_id
                    JOIN business_bid b ON b.id = a.bid_id AND b.tenant_id = a.supplier_tenant_id
                        AND d.current_revision = b.demand_revision
                    JOIN business_demand_revision dr ON dr.demand_id = a.demand_id
                        AND dr.revision = b.demand_revision
                    JOIN business_bid_revision r ON r.bid_id = a.bid_id AND r.tenant_id = a.supplier_tenant_id
                        AND r.revision = a.bid_revision
                    WHERE a.id = ? AND a.employer_tenant_id = ?
                    """, ExecutionContext.require().actorId(), awardId, award.employerTenantId());
            ensureUpdated(snapshotCreated);
            audit.record("bid:confirm", "award", Long.toString(awardId), "SUCCESS");
        } else {
            ensureUpdated(jdbc.update("""
                    UPDATE business_bid_award SET status = 'REJECTED', decision_reason = ?
                    WHERE id = ? AND supplier_tenant_id = ? AND status = 'PENDING_CONFIRMATION'
                    """, normalizedReason(request.reason()), awardId, supplierId));
            audit.record("bid:reject", "award", Long.toString(awardId), "SUCCESS");
        }
        return findAward(awardId, null, supplierId, false).view();
    }

    /** 查询供应商自己的授予或用工方需求下的全部授予。 */
    public List<AwardView> listAwards(long demandId) {
        DemandRow demand = locklessDemand(demandId);
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() == demand.employerTenantId()) {
            requireSelector(demand.employerTenantId());
            return listAwardsForDemand(demandId, demand.employerTenantId());
        }
        requireSupplierBidder();
        return jdbc.query("""
                %s WHERE a.demand_id = ? AND a.supplier_tenant_id = ?
                ORDER BY a.id
                """.formatted(AWARD_SELECT), (result, row) -> mapAward(result).view(), demandId, identity.tenantId());
    }

    /**
     * 将已超过确认期限的授予释放为 EXPIRED；此方法可由定时任务或读取路径显式调用。
     * @param demandId 需求标识
     * @return 本次释放的数量
     */
    @Transactional
    public int expire(long demandId) {
        DemandRow demand = lockDemand(demandId);
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() == null) {
            authorization.requirePlatformPermission("platform:bids:supervise");
        } else if (identity.tenantId() == demand.employerTenantId()) {
            requireSelector(demand.employerTenantId());
        } else {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return expirePendingForDemand(demandId);
    }

    /** 查询双方可见的 BP-05 快照，快照一经创建不因需求关闭而删除。 */
    public List<SnapshotView> snapshots(long demandId) {
        DemandRow demand = locklessDemand(demandId);
        ExecutionContext.Identity identity = ExecutionContext.require();
        boolean employer = identity.tenantId() == demand.employerTenantId();
        if (employer) {
            requireSelector(demand.employerTenantId());
        } else {
            requireSupplierBidder();
        }
        String sql = employer
                ? "SELECT id, award_id, demand_id, employer_tenant_id, supplier_tenant_id, bid_id, bid_revision, "
                + "awarded_quantity, snapshot_status, confirmed_by, confirmed_at, snapshot_payload FROM business_bp05_snapshot "
                + "WHERE demand_id = ? AND employer_tenant_id = ? ORDER BY id"
                : "SELECT id, award_id, demand_id, employer_tenant_id, supplier_tenant_id, bid_id, bid_revision, "
                + "awarded_quantity, snapshot_status, confirmed_by, confirmed_at, snapshot_payload FROM business_bp05_snapshot "
                + "WHERE demand_id = ? AND supplier_tenant_id = ? ORDER BY id";
        return jdbc.query(sql, (result, row) -> mapSnapshot(result), demandId, identity.tenantId());
    }

    /**
     * 记录平台监督和证据引用；平台身份不能拥有需求组织上下文，且不能代替交易方作决定。
     * @param demandId 需求标识
     * @param action 监督动作标识
     * @param evidenceReference 外部证据引用
     * @return 新监督记录标识
     * @throws BusinessException 缺少监督权限、证据无效或发生利益冲突
     */
    public long supervise(long demandId, String action, String evidenceReference) {
        SupervisionResult result = supervisionTransaction.execute(
                status -> superviseOnce(demandId, action, evidenceReference));
        if (result == null) {
            throw new BusinessException(PlatformError.INTERNAL_ERROR);
        }
        if (result.error() != null) {
            throw new BusinessException(result.error());
        }
        return result.eventId();
    }

    /** 在独立事务中写监督事实，回避结果提交后再向调用方返回拒绝。 */
    private SupervisionResult superviseOnce(long demandId, String action, String evidenceReference) {
        authorization.requirePlatformPermission("platform:bids:supervise");
        DemandRow demand = lockDemand(demandId);
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (identity.tenantId() != null
                || (demand.submittedBy() != null && identity.actorId() == demand.submittedBy())
                || action == null || !action.matches("[A-Za-z0-9:._-]{1,64}")
                || evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 200) {
            audit.record("bid:supervise", "demand", Long.toString(demandId), "DENIED");
            return new SupervisionResult(null, PlatformError.ACCESS_DENIED);
        }
        if (identity.actorId() == demand.createdBy()
                || hasPriorBusinessRelationship(demand.id(), demand.employerTenantId(), identity.actorId())) {
            long eventId = insertSupervisionEvent(demandId, demand.employerTenantId(),
                    "RECUSAL", evidenceReference, identity.actorId(), "RECUSAL");
            audit.record("bid:supervise", "demand", Long.toString(demandId), "DENIED");
            return new SupervisionResult(eventId, PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
        long eventId = insertSupervisionEvent(demandId, demand.employerTenantId(),
                action, evidenceReference, identity.actorId(), "RECORDED");
        audit.record("bid:supervise", "demand", Long.toString(demandId), "SUCCESS");
        return new SupervisionResult(eventId, null);
    }

    /** 追加监督事件，调用方事务决定其与审计结果的提交边界。 */
    private long insertSupervisionEvent(long demandId, long employerTenantId, String action,
                                        String evidenceReference, long actorId, String outcome) {
        long eventId = ids.nextId();
        jdbc.update("""
                INSERT INTO business_bid_supervision_event
                    (id, demand_id, employer_tenant_id, action, evidence_reference, actor_id, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, demandId, employerTenantId, action, evidenceReference.trim(), actorId, outcome);
        return eventId;
    }

    /** 需求暂停时禁止新的交易动作，关闭时将尚未确认的授予和应标关闭。 */
    @Transactional
    public void closeForDemand(long demandId) {
        jdbc.update("UPDATE business_bid SET status = 'CLOSED', updated_at = CURRENT_TIMESTAMP WHERE demand_id = ? AND status <> 'CLOSED'", demandId);
        jdbc.update("UPDATE business_bid_award SET status = 'EXPIRED', decision_reason = 'DEMAND_CLOSED' "
                + "WHERE demand_id = ? AND status = 'PENDING_CONFIRMATION'", demandId);
    }

    /** 要求当前身份具备供应商应标成员角色和业务权限。 */
    private long requireSupplierBidder() {
        ExecutionContext.checkPermission("organization:bids:write");
        long tenant = ExecutionContext.requireTenant();
        OrganizationAuthorization.Membership membership = authorization.membership(tenant,
                OrganizationAuthorization.Membership.adminOr(MembershipRole.SUPPLIER_BIDDER));
        if (membership.organizationRole() != OrganizationRole.LABOR_SUPPLIER
                || membership.organizationStatus() != com.company.labor.modules.identity.OrganizationStatus.APPROVED) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        return tenant;
    }

    /** 要求用工方具备独立选标角色。 */
    private void requireSelector(long organizationId) {
        ExecutionContext.checkPermission("organization:bids:select");
        OrganizationAuthorization.Membership membership = authorization.membership(organizationId,
                OrganizationAuthorization.Membership.adminOr(MembershipRole.DEMAND_SELECTOR));
        if (membership.organizationRole() != OrganizationRole.EMPLOYER
                || membership.organizationStatus() != com.company.labor.modules.identity.OrganizationStatus.APPROVED) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
    }

    /** 锁定需求，读取当前状态和截止窗口；锁是跨应标提交和选标配额的并发边界。 */
    private DemandRow lockDemand(long demandId) {
        DemandRow row = jdbc.query("""
                SELECT d.id, d.organization_id, d.status, d.created_by, d.submitted_by, d.current_revision,
                       r.quantity, r.bid_deadline,
                       r.confirmation_deadline
                FROM business_demand d JOIN business_demand_revision r
                  ON r.demand_id = d.id AND r.tenant_id = d.tenant_id AND r.revision = d.current_revision
                WHERE d.id = ? AND d.tenant_id = d.organization_id FOR UPDATE OF d
                """, result -> result.next() ? mapDemand(result) : null, demandId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 无锁读取需求，供只读列表使用。 */
    private DemandRow locklessDemand(long demandId) {
        DemandRow row = jdbc.query("""
                SELECT d.id, d.organization_id, d.status, d.created_by, d.submitted_by, d.current_revision,
                       r.quantity, r.bid_deadline,
                       r.confirmation_deadline
                FROM business_demand d JOIN business_demand_revision r
                  ON r.demand_id = d.id AND r.tenant_id = d.tenant_id AND r.revision = d.current_revision
                WHERE d.id = ? AND d.tenant_id = d.organization_id
                """, result -> result.next() ? mapDemand(result) : null, demandId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 检查供应商应标窗口，SUSPENDED 和 CLOSED 明确拒绝新增应标。 */
    private void ensureBidWindowOpen(DemandRow demand, Instant now) {
        if (demand.status() != DemandState.OPEN_FOR_BID || !demand.bidDeadline().isAfter(now)) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
    }

    /** 检查报价字段边界和显式接受条款。 */
    private void validateSubmit(SubmitRequest request) {
        if (!Boolean.TRUE.equals(request.acceptedTerms()) || request.taxRate().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /** 查找当前供应商可在截止前复用的应标并可选加行锁；撤回后重提仍追加到同一历史链。 */
    private BidRow findReusableBid(long demandId, long demandRevision, long supplierId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT b.id, b.demand_id, b.demand_revision, b.employer_tenant_id, b.supplier_tenant_id,
                       b.current_revision,
                       b.status, r.committed_quantity, r.commitment, r.deviation_items, r.proposal,
                       r.quoted_amount, r.quote_currency, r.quote_unit, r.tax_rate, r.payment_term,
                       r.payment_arrangement, r.created_at
                FROM business_bid b JOIN business_bid_revision r ON r.bid_id = b.id
                    AND r.tenant_id = b.tenant_id AND r.revision = b.current_revision
                WHERE b.demand_id = ? AND b.demand_revision = ? AND b.tenant_id = ?
                  AND b.status IN ('ACTIVE', 'WITHDRAWN')
                """ + suffix, result -> result.next() ? mapBid(result) : null,
                demandId, demandRevision, supplierId);
    }

    /** 查找供应商可见的应标。 */
    private BidRow findBid(long bidId, long supplierId) {
        BidRow row = jdbc.query("""
                SELECT b.id, b.demand_id, b.demand_revision, b.employer_tenant_id,
                       b.supplier_tenant_id, b.current_revision,
                       b.status, r.committed_quantity, r.commitment, r.deviation_items, r.proposal,
                       r.quoted_amount, r.quote_currency, r.quote_unit, r.tax_rate, r.payment_term,
                       r.payment_arrangement, r.created_at
                FROM business_bid b JOIN business_bid_revision r ON r.bid_id = b.id
                    AND r.tenant_id = b.tenant_id AND r.revision = b.current_revision
                WHERE b.id = ? AND b.tenant_id = ?
                """, result -> result.next() ? mapBid(result) : null, bidId, supplierId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 查找需求下的应标并可选加锁，防止选标时版本被修改。 */
    private BidRow findBidForDemand(long bidId, long demandId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        BidRow row = jdbc.query("""
                SELECT b.id, b.demand_id, b.demand_revision, b.employer_tenant_id,
                       b.supplier_tenant_id, b.current_revision,
                       b.status, r.committed_quantity, r.commitment, r.deviation_items, r.proposal,
                       r.quoted_amount, r.quote_currency, r.quote_unit, r.tax_rate, r.payment_term,
                       r.payment_arrangement, r.created_at
                FROM business_bid b JOIN business_bid_revision r ON r.bid_id = b.id
                    AND r.tenant_id = b.tenant_id AND r.revision = b.current_revision
                WHERE b.id = ? AND b.demand_id = ? AND b.employer_tenant_id = ?
                """ + suffix, result -> result.next() ? mapBid(result) : null,
                bidId, demandId, ExecutionContext.requireTenant());
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 查询单个授予并限制到指定一方组织。 */
    private AwardRow findAward(long awardId, Long employerId, Long supplierId, boolean lock) {
        StringBuilder sql = new StringBuilder(AWARD_SELECT).append(" WHERE a.id = ?");
        if (employerId != null) {
            sql.append(" AND a.employer_tenant_id = ?");
        }
        if (supplierId != null) {
            sql.append(" AND a.supplier_tenant_id = ?");
        }
        if (lock) {
            sql.append(" FOR UPDATE OF a");
        }
        Object[] args = employerId != null && supplierId != null
                ? new Object[]{awardId, employerId, supplierId}
                : employerId != null ? new Object[]{awardId, employerId} : new Object[]{awardId, supplierId};
        AwardRow row = jdbc.query(sql.toString(), result -> result.next() ? mapAward(result) : null, args);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 查询需求下的授予列表。 */
    private List<AwardView> listAwardsForDemand(long demandId, long employerId) {
        return jdbc.query(AWARD_SELECT + " WHERE a.demand_id = ? AND a.employer_tenant_id = ? ORDER BY a.id",
                (result, row) -> mapAward(result).view(), demandId, employerId);
    }

    /** 批量释放需求下已经超时且尚未决定的授予。 */
    private int expirePendingForDemand(long demandId) {
        int updated = jdbc.update("""
                UPDATE business_bid_award SET status = 'EXPIRED', decision_reason = 'CONFIRMATION_TIMEOUT'
                WHERE demand_id = ? AND status = 'PENDING_CONFIRMATION'
                  AND confirmation_deadline <= clock_timestamp()
                """, demandId);
        if (updated > 0) {
            audit.record("bid:expire", "demand", Long.toString(demandId), "SUCCESS");
        }
        return updated;
    }

    /** 将 JDBC 需求行转换为内部快照。 */
    private DemandRow mapDemand(ResultSet result) throws SQLException {
        String status = result.getString("status");
        DemandState state = switch (status) {
            case "OPEN_FOR_BID" -> DemandState.OPEN_FOR_BID;
            case "SUSPENDED" -> DemandState.SUSPENDED;
            case "CLOSED" -> DemandState.CLOSED;
            default -> DemandState.OTHER;
        };
        return new DemandRow(result.getLong("id"), result.getLong("organization_id"),
                state, result.getLong("created_by"), result.getObject("submitted_by", Long.class),
                result.getLong("current_revision"), result.getBigDecimal("quantity"),
                result.getTimestamp("bid_deadline").toInstant(),
                result.getTimestamp("confirmation_deadline") == null
                        ? null : result.getTimestamp("confirmation_deadline").toInstant());
    }

    /** 将 JDBC 应标行转换为内部快照。 */
    private BidRow mapBid(ResultSet result) throws SQLException {
        return new BidRow(result.getLong("id"), result.getLong("demand_id"),
                result.getObject("demand_revision", Long.class), result.getLong("employer_tenant_id"),
                result.getLong("supplier_tenant_id"),
                result.getLong("current_revision"), BidStatus.valueOf(result.getString("status")),
                result.getBigDecimal("committed_quantity"), result.getString("commitment"),
                result.getString("deviation_items"), result.getString("proposal"),
                result.getBigDecimal("quoted_amount"), result.getString("quote_currency"),
                result.getString("quote_unit"), result.getBigDecimal("tax_rate"),
                result.getString("payment_term"), result.getString("payment_arrangement"),
                result.getTimestamp("created_at").toInstant());
    }

    /** 将 JDBC 授予行转换为内部快照。 */
    private AwardRow mapAward(ResultSet result) throws SQLException {
        Timestamp confirmed = result.getTimestamp("confirmed_at");
        return new AwardRow(result.getLong("id"), result.getLong("demand_id"),
                result.getLong("employer_tenant_id"), result.getLong("bid_id"),
                result.getLong("supplier_tenant_id"), result.getLong("bid_revision"),
                result.getBigDecimal("awarded_quantity"), result.getTimestamp("confirmation_deadline").toInstant(),
                AwardStatus.valueOf(result.getString("status")), result.getTimestamp("decided_at").toInstant(),
                confirmed == null ? null : confirmed.toInstant(),
                new ConfirmationTerms(result.getLong("demand_revision"), result.getString("demand_title"),
                        result.getString("demand_service_scope"), result.getString("demand_location"),
                        result.getTimestamp("starts_at").toInstant(), result.getTimestamp("ends_at").toInstant(),
                        result.getBigDecimal("demand_quantity"), result.getString("quantity_unit"),
                        result.getString("target"), result.getString("pricing_mode"),
                        result.getString("measurement_basis"), result.getString("settlement_cycle"),
                        result.getTimestamp("bid_deadline").toInstant(),
                        result.getTimestamp("demand_confirmation_deadline").toInstant(),
                        result.getString("selection_rule"), result.getBigDecimal("committed_quantity"),
                        result.getString("commitment"), result.getString("deviation_items"),
                        result.getString("proposal"), result.getBigDecimal("quoted_amount"),
                        result.getString("quote_currency"), result.getString("quote_unit"),
                        result.getBigDecimal("tax_rate"), result.getString("payment_term"),
                        result.getString("payment_arrangement")));
    }

    /** 将 JDBC BP-05 快照行转换为 API 结果。 */
    private SnapshotView mapSnapshot(ResultSet result) throws SQLException {
        return new SnapshotView(result.getLong("id"), result.getLong("award_id"), result.getLong("demand_id"),
                result.getLong("employer_tenant_id"), result.getLong("supplier_tenant_id"),
                result.getLong("bid_id"), result.getLong("bid_revision"),
                result.getBigDecimal("awarded_quantity"), result.getString("snapshot_status"),
                result.getLong("confirmed_by"), result.getTimestamp("confirmed_at").toInstant(),
                result.getString("snapshot_payload"));
    }

    /** 使用数据库时钟判断截止，避免应用节点时钟漂移改变边界结果。 */
    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** 检查监督人是否属于交易组织，或曾审核过该需求。 */
    private boolean hasPriorBusinessRelationship(long demandId, long employerTenantId, long actorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM business_membership m
                    WHERE m.actor_id = ? AND m.organization_id IN (
                        SELECT ? UNION SELECT supplier_tenant_id FROM business_bid WHERE demand_id = ?
                    )
                ) OR EXISTS (
                    SELECT 1 FROM business_review_decision
                    WHERE resource_type = 'DEMAND' AND resource_id = ? AND reviewer_id = ?
                )
                """, Boolean.class, actorId, employerTenantId, demandId, demandId, actorId));
    }

    /** 标准化可审计的决定理由；接受时允许省略。 */
    private String normalizedReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    /** 保证并发更新只影响预期行。 */
    private void ensureUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
    }

    /** 需求生命周期的服务内部状态，避免把需求模块的实现类型暴露到 BP-04。 */
    private enum DemandState {
        /** 已发布且可接收应标。 */
        OPEN_FOR_BID,
        /** 已暂停。 */
        SUSPENDED,
        /** 已关闭。 */
        CLOSED,
        /** 其他状态。 */
        OTHER
    }

    /** 需求并发检查所需的最小只读字段。 */
    private record DemandRow(long id, long employerTenantId, DemandState status, long createdBy,
                             Long submittedBy, long revision, BigDecimal quantity,
                             Instant bidDeadline, Instant confirmationDeadline) {
        /**
         * 把数据库状态转换为严格的 BP-04 门禁值。
         * @param id 需求标识
         * @param employerTenantId 用工方组织
         * @param status 需求状态
         * @param createdBy 创建人
         * @param submittedBy 最近提交审核的成员
         * @param revision 当前需求版本
         * @param quantity 需求总量
         * @param bidDeadline 应标截止时间
         * @param confirmationDeadline 供应商确认截止时间
         */
        private DemandRow {
            if (status == null) {
                status = DemandState.OTHER;
            }
        }
    }

    /** 应标的当前版本内部快照。 */
    private record BidRow(long id, long demandId, Long demandRevision, long employerTenantId, long supplierTenantId,
                          long revision, BidStatus status, BigDecimal committedQuantity, String commitment,
                          String deviationItems, String proposal, BigDecimal quotedAmount, String quoteCurrency,
                          String quoteUnit, BigDecimal taxRate, String paymentTerm,
                          String paymentArrangement, Instant createdAt) {
        /** 返回公开应标视图。 */
        private BidView view() {
            return new BidView(id, demandId, supplierTenantId, revision, status, committedQuantity, commitment,
                    deviationItems, proposal, quotedAmount, quoteCurrency, quoteUnit, taxRate,
                    paymentTerm, paymentArrangement, createdAt);
        }
    }

    /** 选标授予的当前状态内部快照。 */
    private record AwardRow(long id, long demandId, long employerTenantId, long bidId, long supplierTenantId,
                            long bidRevision, BigDecimal awardedQuantity, Instant confirmationDeadline,
                            AwardStatus status, Instant decidedAt, Instant confirmedAt, ConfirmationTerms terms) {
        /** 返回与交易方一致的授予视图。 */
        private AwardView view() {
            return new AwardView(id, demandId, bidId, supplierTenantId, bidRevision, awardedQuantity,
                    confirmationDeadline, status, decidedAt, confirmedAt, terms);
        }
    }

    /** 监督事务完成后交由边界方法返回或抛出拒绝结果。 */
    private record SupervisionResult(Long eventId, PlatformError error) {
        /**
         * 保存已提交监督事件或权限拒绝。
         * @param eventId 新增的监督记录标识，无事件时为空
         * @param error 应在事务提交后返回的权限错误，无错误时为空
         */
        private SupervisionResult {
        }
    }
}
