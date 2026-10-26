package com.company.labor.modules.platform;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.modules.auth.PlatformAuthorizationService;
import com.company.labor.modules.platform.PlatformAuditDtos.AuditEvent;
import com.company.labor.modules.platform.PlatformAuditDtos.CoverageItem;
import com.company.labor.modules.platform.PlatformAuditDtos.CreateFindingRequest;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingClaim;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingDetail;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingEntry;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingSummary;
import com.company.labor.modules.platform.PlatformAuditDtos.QueryResult;
import com.company.labor.modules.platform.PlatformAuditDtos.ReviewRequest;
import com.company.labor.modules.platform.PlatformAuditDtos.Stage;
import com.company.labor.modules.platform.PlatformAuditDtos.ResponseRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 提供跨组织监察查询、覆盖目录和独立监察发现闭环；不切换组织租户上下文。 */
@Service
public class PlatformAuditService {
    /** 结构化审计查询、发现和目录的数据库入口。 */
    private final JdbcTemplate jdbc;
    /** 实时解析平台岗位和权限的授权服务。 */
    private final PlatformAuthorizationService authorization;
    /** 事项历史的专用内部读取服务。 */
    private final ReviewCaseHistoryService history;
    /** 追加式审计记录器；查询、发现处理和拒绝都必须留痕。 */
    private final AuditRecorder audit;
    /** 监察查询和发现使用的共享编号生成器。 */
    private final SequenceIds ids;

    /**
     * 装配监察领域服务依赖。
     * @param jdbc 数据库访问模板
     * @param authorization 实时平台岗位授权服务
     * @param history 审核事项历史服务
     * @param audit 追加式审计记录器
     * @param ids 统一数据库序列编号服务
     */
    public PlatformAuditService(JdbcTemplate jdbc, PlatformAuthorizationService authorization,
                                ReviewCaseHistoryService history, AuditRecorder audit, SequenceIds ids) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.history = history;
        this.audit = audit;
        this.ids = ids;
    }

    /**
     * 按结构化条件跨组织查询审计事件，并以审计编号游标分页。
     * @param organizationId 组织编号，可为空
     * @param reviewCaseId 事项编号，可为空
     * @param resourceId 资源编号，可为空
     * @param resourceVersion 业务版本，可为空
     * @param eventActorId 事件操作者，可为空
     * @param actorRole 平台岗位，可为空
     * @param action 固定动作码，可为空
     * @param outcome 事件结果，可为空
     * @param fromAt 起始时间，可为空
     * @param toAt 结束时间，可为空
     * @param beforeId 上一页最后事件编号，可为空
     * @param limit 页大小
     * @param purposeCode 访问用途码
     * @return 查询记录编号、事件和下一页游标
     */
    @Transactional
    public QueryResult query(Long organizationId, String reviewCaseId, String resourceId, Long resourceVersion,
                             Long eventActorId, String actorRole, String action, String outcome,
                             Instant fromAt, Instant toAt, String beforeId, int limit, String purposeCode) {
        authorization.requirePermission("platform:audit:read");
        Filter filter = validateFilter(organizationId, reviewCaseId, resourceId, resourceVersion, eventActorId,
                actorRole, action, outcome, fromAt, toAt, beforeId, limit, purposeCode);
        ExecutionContext.Identity identity = ExecutionContext.require();
        long queryId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_audit_query
                    (id, actor_id, organization_id, review_case_id, resource_id, resource_version,
                     event_actor_id, actor_role, action, outcome, from_at, to_at, purpose_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, queryId, identity.actorId(), filter.organizationId(), filter.reviewCaseId(),
                filter.resourceId(), filter.resourceVersion(), filter.eventActorId(), filter.actorRole(),
                filter.action(), filter.outcome(), filter.fromAt() == null ? null : Timestamp.from(filter.fromAt()),
                filter.toAt() == null ? null : Timestamp.from(filter.toAt()), filter.purposeCode());
        try {
            List<Object> arguments = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT id, actor_id, actor_type, tenant_id, action, resource_type, resource_id,
                           outcome, review_case_id, resource_version, actor_role, reason_code,
                           purpose_code, created_at
                    FROM foundation_audit WHERE 1 = 1
                    """);
            appendFilter(sql, arguments, filter);
            sql.append(" ORDER BY id DESC LIMIT ?");
            arguments.add(filter.limit() + 1);
            List<AuditEvent> events = jdbc.query(sql.toString(), (result, row) -> mapAuditEvent(result),
                    arguments.toArray());
            String nextCursor = null;
            if (events.size() > filter.limit()) {
                events = new ArrayList<>(events.subList(0, filter.limit()));
                nextCursor = events.get(events.size() - 1).eventId();
            }
            audit.record("platform:audit:query", "platform_audit_query", Long.toString(queryId), "SUCCESS",
                    new AuditRecorder.AuditDetails(null, null, null, null, null, null, "AUDIT_QUERY",
                            filter.purposeCode()));
            return new QueryResult(Long.toString(queryId), List.copyOf(events), nextCursor);
        } catch (RuntimeException exception) {
            recordFailure("platform:audit:query", "platform_audit_query", Long.toString(queryId),
                    "AUDIT_QUERY_FAILED", filter.purposeCode());
            throw exception;
        }
    }

    /**
     * 读取全模块审计事件目录并显示最后实际事件；没有测量来源的延迟返回空值。
     * @return 审计覆盖目录
     */
    @Transactional
    public List<CoverageItem> coverage() {
        authorization.requirePermission("platform:audit:coverage:read");
        List<CoverageItem> result = jdbc.query("""
                SELECT c.event_code, c.module_code, c.description, c.status,
                       last_event.id AS last_event_id, last_event.created_at AS last_event_at
                FROM foundation_audit_catalog c
                LEFT JOIN LATERAL (
                    SELECT a.id, a.created_at FROM foundation_audit a
                    WHERE a.action = c.event_code ORDER BY a.id DESC LIMIT 1
                ) last_event ON TRUE
                ORDER BY c.module_code, c.event_code
                """, (row, index) -> new CoverageItem(
                row.getString("event_code"), row.getString("module_code"), row.getString("description"),
                row.getString("status"), stringValue(row, "last_event_id"), instantOrNull(row, "last_event_at"),
                null));
        audit.record("platform:audit:coverage:read", "foundation_audit_catalog", "catalog", "SUCCESS",
                new AuditRecorder.AuditDetails(null, null, null, null, null, null, "COVERAGE_READ",
                        "AUDIT_COVERAGE"));
        return List.copyOf(result);
    }

    /**
     * 读取单一事项的完整内部历史；不从当前组织上下文推断租户范围。
     * @param reviewCaseId 稳定事项编号
     * @return 完整事项历史
     */
    @Transactional
    public ReviewCaseHistoryDtos.View history(long reviewCaseId) {
        authorization.requirePermission("platform:audit:read");
        ReviewCaseHistoryDtos.View result = history.platformAuditHistory(reviewCaseId);
        audit.record("platform:audit:query", "review_case", Long.toString(reviewCaseId), "SUCCESS",
                new AuditRecorder.AuditDetails(Long.toString(reviewCaseId), null, null, null,
                        result.currentVersion(), null, "AUDIT_HISTORY_READ", "AUDIT_HISTORY"));
        return result;
    }

    /**
     * 读取监察发现摘要队列。
     * @param workflowStatus 可选工作流状态
     * @return 最多 100 条发现摘要
     */
    @Transactional(readOnly = true)
    public List<FindingSummary> findings(String workflowStatus) {
        authorization.requirePermission("platform:audit:read");
        if (workflowStatus != null && !workflowStatus.matches("[A-Z_]{1,32}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        String sql = """
                SELECT id, review_case_id, resource_type, resource_id, resource_version, title,
                       fact_status, workflow_status, created_by, created_at, updated_at
                FROM platform_audit_finding
                """ + (workflowStatus == null ? "" : "WHERE workflow_status = ?\n")
                + "ORDER BY created_at DESC, id DESC LIMIT 100";
        List<FindingSummary> result = workflowStatus == null
                ? jdbc.query(sql, (row, index) -> mapFinding(row))
                : jdbc.query(sql, (row, index) -> mapFinding(row), workflowStatus);
        return List.copyOf(result);
    }

    /**
     * 读取单一监察发现及其追加式处理记录。
     * @param findingId 发现编号
     * @return 发现详情
     */
    @Transactional(readOnly = true)
    public FindingDetail finding(long findingId) {
        authorization.requirePermission("platform:audit:read");
        FindingSummary summary = requireFinding(findingId, false);
        List<FindingEntry> entries = jdbc.query("""
                SELECT id, entry_type, actor_id, content, fact_status, created_at
                FROM platform_audit_finding_entry WHERE finding_id = ? ORDER BY created_at, id
                """, (row, index) -> mapEntry(row), findingId);
        FindingClaim claim = activeClaim(findingId);
        return new FindingDetail(summary, List.copyOf(entries), claim);
    }

    /**
     * 创建与已存在事项、版本、审计事件和材料严格关联的监察发现。
     * @param request 发现正文和结构化关联
     * @return 新发现摘要
     */
    @Transactional
    public FindingSummary createFinding(CreateFindingRequest request) {
        authorization.requirePermission("platform:audit:findings:create");
        if (request == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        long reviewCaseId = parseRequired(request.reviewCaseId());
        CaseAssociation association = requireCaseAssociation(reviewCaseId);
        Long requestedVersion = parseOptional(request.resourceVersion());
        Long eventId = parseOptional(request.eventId());
        Long evidenceId = parseOptional(request.evidenceId());
        Long eventVersion = validateEvent(eventId, association, requestedVersion);
        Long evidenceVersion = validateEvidence(evidenceId, association, requestedVersion);
        Long resourceVersion = requestedVersion != null ? requestedVersion
                : eventVersion != null ? eventVersion : evidenceVersion;
        if (eventVersion != null && evidenceVersion != null && !eventVersion.equals(evidenceVersion)) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        long findingId = ids.nextId();
        ExecutionContext.Identity identity = ExecutionContext.require();
        jdbc.update("""
                INSERT INTO platform_audit_finding
                    (id, tenant_id, review_case_id, resource_type, resource_id, resource_version,
                     event_id, evidence_id, title, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, findingId, association.tenantId(), reviewCaseId, association.resourceType(),
                association.resourceId(), resourceVersion, eventId, evidenceId, request.title().trim(),
                request.description().trim(), identity.actorId());
        jdbc.update("""
                INSERT INTO platform_audit_finding_entry
                    (id, finding_id, entry_type, actor_id, content, fact_status)
                VALUES (?, ?, 'CREATED', ?, ?, 'UNCONFIRMED')
                """, ids.nextId(), findingId, identity.actorId(), request.description().trim());
        audit.record("platform:audit:finding:created", "platform_audit_finding", Long.toString(findingId),
                "SUCCESS", findingDetails(findingId, reviewCaseId, resourceVersion, "FINDING_CREATED"));
        return requireFinding(findingId, false);
    }

    /**
     * 原子认领监察发现回应或复核阶段；同一账号和幂等键重放返回原认领。
     * @param findingId 发现编号
     * @param stage RESPONSE 或 REVIEW
     * @param idempotencyKey 幂等键
     * @return 认领结果
     */
    @Transactional
    public FindingClaim claimFinding(long findingId, Stage stage, String idempotencyKey) {
        if (stage == null || idempotencyKey == null || !idempotencyKey.matches("[a-zA-Z0-9:._-]{1,128}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        authorization.requirePermission(stage == Stage.RESPONSE
                ? "platform:audit:findings:respond" : "platform:audit:findings:review");
        ExecutionContext.Identity identity = ExecutionContext.require();
        FindingRow finding = lockFinding(findingId);
        Long existing = jdbc.query("""
                SELECT claim_id FROM platform_audit_finding_claim_request
                WHERE finding_id = ? AND stage = ? AND actor_id = ? AND idempotency_key = ?
                """, result -> result.next() ? result.getLong("claim_id") : null,
                findingId, stage.name(), identity.actorId(), idempotencyKey);
        if (existing != null) {
            return requireClaim(existing);
        }
        String expectedStatus = stage == Stage.RESPONSE ? "WAITING_RESPONSE" : "WAITING_REVIEW";
        String claimedStatus = stage == Stage.RESPONSE ? "RESPONSE_CLAIMED" : "REVIEW_CLAIMED";
        if (!expectedStatus.equals(finding.workflowStatus())) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (stage == Stage.REVIEW && reviewConflict(finding, identity.actorId())) {
            audit.record("platform:audit:finding:updated", "platform_audit_finding", Long.toString(findingId),
                    "DENIED", findingDetails(findingId, finding.reviewCaseId(), finding.resourceVersion(),
                            "REVIEW_RECUSAL_REQUIRED"));
            throw new BusinessException(PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
        Long active = jdbc.query("""
                SELECT id FROM platform_audit_finding_claim
                WHERE finding_id = ? AND stage = ? AND status = 'ACTIVE'
                """, result -> result.next() ? result.getLong("id") : null, findingId, stage.name());
        if (active != null) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        long claimId = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_audit_finding_claim
                    (id, finding_id, stage, actor_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?)
                """, claimId, findingId, stage.name(), identity.actorId(), idempotencyKey);
        jdbc.update("""
                INSERT INTO platform_audit_finding_claim_request
                    (id, finding_id, stage, actor_id, idempotency_key, claim_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ids.nextId(), findingId, stage.name(), identity.actorId(), idempotencyKey, claimId);
        jdbc.update("UPDATE platform_audit_finding SET workflow_status = ?, updated_at = clock_timestamp() WHERE id = ?",
                claimedStatus, findingId);
        audit.record("platform:audit:finding:updated", "platform_audit_finding", Long.toString(findingId),
                "SUCCESS", findingDetails(findingId, finding.reviewCaseId(), finding.resourceVersion(),
                        stage == Stage.RESPONSE ? "RESPONSE_CLAIMED" : "REVIEW_CLAIMED"));
        return requireClaim(claimId);
    }

    /**
     * 在有效回应认领范围内追加回应，并把发现推进到等待独立复核。
     * @param findingId 发现编号
     * @param request 回应内容
     * @return 更新后的发现详情
     */
    @Transactional
    public FindingDetail respond(long findingId, ResponseRequest request) {
        authorization.requirePermission("platform:audit:findings:respond");
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        FindingRow finding = lockFinding(findingId);
        ClaimRow claim = activeClaimRow(findingId, Stage.RESPONSE, identity.actorId());
        if (!"RESPONSE_CLAIMED".equals(finding.workflowStatus()) || claim == null) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        jdbc.update("""
                INSERT INTO platform_audit_finding_entry (id, finding_id, entry_type, actor_id, content)
                VALUES (?, ?, 'RESPONSE', ?, ?)
                """, ids.nextId(), findingId, identity.actorId(), request.content().trim());
        closeClaim(claim.id(), "COMPLETED", "RESPONSE_SUBMITTED");
        jdbc.update("UPDATE platform_audit_finding SET workflow_status = 'WAITING_REVIEW', updated_at = clock_timestamp() WHERE id = ?",
                findingId);
        audit.record("platform:audit:finding:updated", "platform_audit_finding", Long.toString(findingId),
                "SUCCESS", findingDetails(findingId, finding.reviewCaseId(), finding.resourceVersion(),
                        "RESPONSE_SUBMITTED"));
        return finding(findingId);
    }

    /**
     * 在有效复核认领范围内追加复核和关闭记录，不改变任何业务状态或原审计事件。
     * @param findingId 发现编号
     * @param request 事实结论和复核说明
     * @return 关闭后的发现详情
     */
    @Transactional
    public FindingDetail review(long findingId, ReviewRequest request) {
        authorization.requirePermission("platform:audit:findings:review");
        if (request == null || request.factStatus() == null || request.content() == null
                || request.content().isBlank()) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        FindingRow finding = lockFinding(findingId);
        ClaimRow claim = activeClaimRow(findingId, Stage.REVIEW, identity.actorId());
        if (!"REVIEW_CLAIMED".equals(finding.workflowStatus()) || claim == null
                || reviewConflict(finding, identity.actorId())) {
            throw new BusinessException(PlatformError.INDEPENDENT_REVIEW_REQUIRED);
        }
        jdbc.update("""
                INSERT INTO platform_audit_finding_entry (id, finding_id, entry_type, actor_id, content, fact_status)
                VALUES (?, ?, 'REVIEW', ?, ?, ?)
                """, ids.nextId(), findingId, identity.actorId(), request.content().trim(),
                request.factStatus().name());
        jdbc.update("""
                INSERT INTO platform_audit_finding_entry (id, finding_id, entry_type, actor_id, content, fact_status)
                VALUES (?, ?, 'CLOSED', ?, ?, ?)
                """, ids.nextId(), findingId, identity.actorId(), "监察发现复核已完成", request.factStatus().name());
        closeClaim(claim.id(), "COMPLETED", "REVIEW_COMPLETED");
        jdbc.update("""
                UPDATE platform_audit_finding
                SET fact_status = ?, workflow_status = 'CLOSED', updated_at = clock_timestamp()
                WHERE id = ?
                """, request.factStatus().name(), findingId);
        audit.record("platform:audit:finding:updated", "platform_audit_finding", Long.toString(findingId),
                "SUCCESS", findingDetails(findingId, finding.reviewCaseId(), finding.resourceVersion(),
                        "REVIEW_COMPLETED"));
        return finding(findingId);
    }

    /** 校验查询过滤器并转换为不可变内部对象。 */
    private Filter validateFilter(Long organizationId, String reviewCaseId, String resourceId, Long resourceVersion,
                                  Long eventActorId, String actorRole, String action, String outcome,
                                  Instant fromAt, Instant toAt, String beforeId, int limit, String purposeCode) {
        if (organizationId != null && organizationId <= 0 || resourceVersion != null && resourceVersion <= 0
                || eventActorId != null && eventActorId <= 0 || limit < 1 || limit > 100
                || fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        Long caseValue = parseOptional(reviewCaseId);
        Long resourceValue = parseOptional(resourceId);
        Long cursorValue = parseOptional(beforeId);
        validateIdentifier(actorRole, 64);
        validateIdentifier(action, 100);
        validateIdentifier(outcome, 16);
        if (outcome != null && !List.of("SUCCESS", "DENIED", "FAILED", "CONFLICT").contains(outcome)) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        if (purposeCode == null || !purposeCode.matches("[A-Z][A-Z0-9_.:-]{1,63}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return new Filter(organizationId, caseValue, resourceValue,
                resourceVersion, eventActorId, normalize(actorRole), normalize(action), normalize(outcome), fromAt,
                toAt, cursorValue, limit, purposeCode);
    }

    /** 将筛选条件安全追加为固定 SQL 片段和参数，避免拼接用户内容。 */
    private void appendFilter(StringBuilder sql, List<Object> args, Filter filter) {
        if (filter.organizationId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(filter.organizationId());
        }
        if (filter.reviewCaseId() != null) {
            sql.append(" AND review_case_id = ?");
            args.add(filter.reviewCaseId());
        }
        if (filter.resourceId() != null) {
            sql.append(" AND resource_id = ?");
            args.add(Long.toString(filter.resourceId()));
        }
        if (filter.resourceVersion() != null) {
            sql.append(" AND resource_version = ?");
            args.add(filter.resourceVersion());
        }
        if (filter.eventActorId() != null) {
            sql.append(" AND actor_id = ?");
            args.add(filter.eventActorId());
        }
        if (filter.actorRole() != null) {
            sql.append(" AND actor_role = ?");
            args.add(filter.actorRole());
        }
        if (filter.action() != null) {
            sql.append(" AND action = ?");
            args.add(filter.action());
        }
        if (filter.outcome() != null) {
            sql.append(" AND outcome = ?");
            args.add(filter.outcome());
        }
        if (filter.fromAt() != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(filter.fromAt()));
        }
        if (filter.toAt() != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(filter.toAt()));
        }
        if (filter.beforeId() != null) {
            sql.append(" AND id < ?");
            args.add(filter.beforeId());
        }
    }

    /** 校验发现关联的稳定事项。 */
    private CaseAssociation requireCaseAssociation(long reviewCaseId) {
        CaseAssociation association = jdbc.query("""
                SELECT id, tenant_id, resource_type, resource_id FROM platform_review_case WHERE id = ?
                """, result -> result.next() ? new CaseAssociation(result.getLong("id"),
                result.getLong("tenant_id"), result.getString("resource_type"), result.getLong("resource_id"))
                : null, reviewCaseId);
        if (association == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return association;
    }

    /** 校验事件确实属于事项和指定版本。 */
    private Long validateEvent(Long eventId, CaseAssociation association, Long requestedVersion) {
        if (eventId == null) {
            return null;
        }
        return jdbc.query("""
                SELECT resource_version FROM foundation_audit
                WHERE id = ? AND review_case_id = ?
                """, result -> {
            if (!result.next()) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            Long version = result.getObject("resource_version", Long.class);
            if (requestedVersion != null && !Objects.equals(requestedVersion, version)) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            return version;
        }, eventId, Long.toString(association.reviewCaseId()));
    }

    /** 校验材料属于事项资源和指定版本。 */
    private Long validateEvidence(Long evidenceId, CaseAssociation association, Long requestedVersion) {
        if (evidenceId == null) {
            return null;
        }
        return jdbc.query("""
                SELECT resource_version FROM platform_review_evidence
                WHERE id = ? AND tenant_id = ? AND resource_type = ? AND resource_id = ?
                """, result -> {
            if (!result.next()) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            Long version = result.getLong("resource_version");
            if (requestedVersion != null && !Objects.equals(requestedVersion, version)) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            return version;
        }, evidenceId, association.tenantId(), association.resourceType(), association.resourceId());
    }

    /** 锁定发现行，串行化同一发现的认领和回应。 */
    private FindingRow lockFinding(long findingId) {
        if (findingId <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        FindingRow row = jdbc.query("""
                SELECT id, review_case_id, resource_version, workflow_status, created_by
                FROM platform_audit_finding WHERE id = ? FOR UPDATE
                """, result -> result.next() ? new FindingRow(result.getLong("id"),
                result.getLong("review_case_id"), result.getObject("resource_version", Long.class),
                result.getString("workflow_status"), result.getLong("created_by")) : null, findingId);
        if (row == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** 读取发现摘要并在不存在时返回统一资源错误。 */
    private FindingSummary requireFinding(long findingId, boolean lock) {
        if (findingId <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        String sql = """
                SELECT id, review_case_id, resource_type, resource_id, resource_version, title,
                       fact_status, workflow_status, created_by, created_at, updated_at
                FROM platform_audit_finding WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        FindingSummary result = jdbc.query(sql, row -> row.next() ? mapFinding(row) : null, findingId);
        if (result == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return result;
    }

    /** 读取指定认领及其当前状态。 */
    private FindingClaim requireClaim(long claimId) {
        FindingClaim result = jdbc.query("""
                SELECT id, stage, actor_id, status, claimed_at, closed_at, close_reason
                FROM platform_audit_finding_claim WHERE id = ?
                """, row -> row.next() ? mapClaim(row) : null, claimId);
        if (result == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return result;
    }

    /** 读取某发现当前阶段的有效认领。 */
    private FindingClaim activeClaim(long findingId) {
        return jdbc.query("""
                SELECT id, stage, actor_id, status, claimed_at, closed_at, close_reason
                FROM platform_audit_finding_claim
                WHERE finding_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1
                """, row -> row.next() ? mapClaim(row) : null, findingId);
    }

    /** 读取当前账号在某阶段持有的有效认领。 */
    private ClaimRow activeClaimRow(long findingId, Stage stage, long actorId) {
        return jdbc.query("""
                SELECT id, actor_id FROM platform_audit_finding_claim
                WHERE finding_id = ? AND stage = ? AND actor_id = ? AND status = 'ACTIVE'
                """, row -> row.next() ? new ClaimRow(row.getLong("id"), row.getLong("actor_id")) : null,
                findingId, stage.name(), actorId);
    }

    /** 关闭认领并保存单向状态变化理由。 */
    private void closeClaim(long claimId, String status, String reason) {
        if (jdbc.update("""
                UPDATE platform_audit_finding_claim
                SET status = ?, closed_at = clock_timestamp(), close_reason = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, status, reason, claimId) != 1) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
    }

    /** 检查复核人是否与创建人、回应人或关联事件操作者重合。 */
    private boolean reviewConflict(FindingRow finding, long actorId) {
        if (finding.createdBy() == actorId) {
            return true;
        }
        boolean responseActor = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM platform_audit_finding_entry
                    WHERE finding_id = ? AND entry_type = 'RESPONSE' AND actor_id = ?)
                """, Boolean.class, finding.id(), actorId));
        if (responseActor) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform_audit_finding f
                    JOIN foundation_audit a ON a.id = f.event_id
                    WHERE f.id = ? AND a.actor_id = ?
                )
                """, Boolean.class, finding.id(), actorId));
    }

    /** 生成发现关联的审计详情。 */
    private AuditRecorder.AuditDetails findingDetails(long findingId, long reviewCaseId, Long version,
                                                      String reasonCode) {
        return new AuditRecorder.AuditDetails(Long.toString(reviewCaseId), null, Long.toString(findingId), null,
                version, null, reasonCode, "AUDIT_FINDING");
    }

    /** 记录失败事件但不掩盖原始业务异常。 */
    private void recordFailure(String action, String resourceType, String resourceId, String reasonCode,
                               String purposeCode) {
        try {
            audit.record(action, resourceType, resourceId, "FAILED",
                    new AuditRecorder.AuditDetails(null, null, null, null, null, null, reasonCode, purposeCode));
        } catch (RuntimeException ignored) {
            // 审计器已经将失败写入独立故障通道；原始查询异常必须继续返回。
        }
    }

    /** 将数据库审计事件转换为 bigint 字符串输出。 */
    private AuditEvent mapAuditEvent(ResultSet row) throws SQLException {
        return new AuditEvent(Long.toString(row.getLong("id")), stringValue(row, "actor_id"),
                row.getString("actor_type"), stringValue(row, "tenant_id"), row.getString("action"),
                row.getString("resource_type"), row.getString("resource_id"), row.getString("outcome"),
                row.getString("review_case_id"), row.getObject("resource_version", Long.class),
                row.getString("actor_role"), row.getString("reason_code"), row.getString("purpose_code"),
                row.getTimestamp("created_at").toInstant());
    }

    /** 将发现行转换为摘要。 */
    private FindingSummary mapFinding(ResultSet row) throws SQLException {
        return new FindingSummary(Long.toString(row.getLong("id")), nullableString(row, "review_case_id"),
                row.getString("resource_type"), nullableString(row, "resource_id"),
                row.getObject("resource_version", Long.class), row.getString("title"),
                row.getString("fact_status"), row.getString("workflow_status"),
                Long.toString(row.getLong("created_by")), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    /** 将发现追加记录转换为 DTO。 */
    private FindingEntry mapEntry(ResultSet row) throws SQLException {
        return new FindingEntry(Long.toString(row.getLong("id")), row.getString("entry_type"),
                Long.toString(row.getLong("actor_id")), row.getString("content"), row.getString("fact_status"),
                row.getTimestamp("created_at").toInstant());
    }

    /** 将认领行转换为 DTO。 */
    private FindingClaim mapClaim(ResultSet row) throws SQLException {
        String status = row.getString("status");
        return new FindingClaim(Long.toString(row.getLong("id")), row.getString("stage"),
                Long.toString(row.getLong("actor_id")), status, row.getTimestamp("claimed_at").toInstant(),
                timestampOrNull(row, "closed_at"), row.getString("close_reason"), "ACTIVE".equals(status));
    }

    /** 解析正数编号；空值代表没有关联。 */
    private Long parseOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /** 解析必填正数编号。 */
    private long parseRequired(String value) {
        Long parsed = parseOptional(value);
        if (parsed == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return parsed;
    }

    /** 校验可选固定标识。 */
    private void validateIdentifier(String value, int maxLength) {
        if (value != null && !value.matches("[a-zA-Z0-9:._-]{1," + maxLength + "}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /** 清理可选标识的首尾空格。 */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 读取可空 bigint 为字符串。 */
    private String stringValue(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        return value == null ? null : Long.toString(((Number) value).longValue());
    }

    /** 读取可空文本列。 */
    private String nullableString(ResultSet row, String column) throws SQLException {
        return row.getObject(column) == null ? null : row.getString(column);
    }

    /** 读取可空时间列。 */
    private Instant timestampOrNull(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 读取覆盖目录可空时间列。 */
    private Instant instantOrNull(ResultSet row, String column) throws SQLException {
        return timestampOrNull(row, column);
    }

    /** 查询过滤器内部对象。 */
    private record Filter(Long organizationId, Long reviewCaseId, Long resourceId, Long resourceVersion,
                          Long eventActorId, String actorRole, String action, String outcome, Instant fromAt,
                          Instant toAt, Long beforeId, int limit, String purposeCode) {
    }

    /** 创建发现时绑定的不可变事项资源范围。 */
    private record CaseAssociation(long reviewCaseId, long tenantId, String resourceType, long resourceId) {
    }

    /** 发现的最小可锁定状态。 */
    private record FindingRow(long id, long reviewCaseId, Long resourceVersion, String workflowStatus,
                              long createdBy) {
    }

    /** 当前账号在某一阶段持有的认领行。 */
    private record ClaimRow(long id, long actorId) {
    }
}
