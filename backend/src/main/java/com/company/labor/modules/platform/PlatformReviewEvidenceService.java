package com.company.labor.modules.platform;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.persistence.SequenceIds;
import com.company.labor.foundation.storage.EvidenceContentScanner;
import com.company.labor.foundation.storage.PlatformFileAccessScope;
import com.company.labor.foundation.storage.PrivateFileStorage;
import com.company.labor.modules.identity.MembershipRole;
import com.company.labor.modules.identity.OrganizationAuthorization;
import com.company.labor.modules.identity.OrganizationRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 按申请版本绑定已扫描材料，并仅向有效认领任务提供去原件化的受控预览。 */
@Service
public class PlatformReviewEvidenceService {
    /** 版本化证据与任务范围的显式限域数据访问入口。 */
    private final JdbcTemplate jdbc;
    /** 仅允许上传者将本人的组织私有文件提交扫描。 */
    private final PrivateFileStorage files;
    /** 必须明确返回无威胁才允许证据绑定。 */
    private final EvidenceContentScanner scanner;
    /** 每次材料访问前重新检查实时岗位、认领、版本与回避状态。 */
    private final PlatformTaskService tasks;
    /** 组织成员角色和当前组织关系校验。 */
    private final OrganizationAuthorization organizations;
    /** 文件内容只用于服务端 PNG 栅格化。 */
    private final ReviewEvidencePreviewRenderer renderer;
    /** 所有成功和拒绝访问均需写入的追加式审计。 */
    private final AuditRecorder audit;
    /** 业务表使用的统一数据库序列。 */
    private final SequenceIds ids;
    /** 私有文件存储和预览解码均受此上限约束。 */
    private final long maxBytes;

    /**
     * 创建审核证据服务。
     * @param jdbc 限域 SQL 访问入口
     * @param files 私有文件读取服务
     * @param scanner 恶意内容扫描器
     * @param tasks 当前认领任务授权服务
     * @param organizations 组织成员授权服务
     * @param renderer 服务端安全预览渲染器
     * @param audit 追加式审计服务
     * @param ids 统一序列 ID 服务
     * @param properties 私有存储限制配置
     */
    public PlatformReviewEvidenceService(JdbcTemplate jdbc, PrivateFileStorage files,
                                         EvidenceContentScanner scanner, PlatformTaskService tasks,
                                         OrganizationAuthorization organizations,
                                         ReviewEvidencePreviewRenderer renderer, AuditRecorder audit,
                                         SequenceIds ids, FoundationProperties properties) {
        this.jdbc = jdbc;
        this.files = files;
        this.scanner = scanner;
        this.tasks = tasks;
        this.organizations = organizations;
        this.renderer = renderer;
        this.audit = audit;
        this.ids = ids;
        this.maxBytes = properties.getStorage().getMaxBytes();
        if (maxBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("Evidence preview size limit exceeds the in-memory renderer bound");
        }
    }

    /**
     * 扫描并将当前申请方本人上传的文件追加绑定到当前可编辑版本。
     * @param type 组织申请或需求审核资源类型
     * @param resourceId 当前组织或需求编号
     * @param expectedVersion 客户端正在编辑的版本号
     * @param fileId 当前组织成员本人上传的私有文件编号
     * @param category 申请方声明的粗粒度材料分类
     * @return 不含文件名、所有者和原件地址的绑定摘要
     * @throws BusinessException 版本不可编辑、文件非本人上传、类型不支持或扫描未明确通过
     */
    @Transactional
    public ApplicantEvidenceView attach(ReviewCaseType type, long resourceId, long expectedVersion,
                                        String fileId, ReviewEvidenceCategory category) {
        requirePositive(resourceId, expectedVersion);
        if (type == null || category == null || fileId == null || !fileId.matches("[a-f0-9]{32}")) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        ResourceSnapshot resource = null;
        try {
            resource = requireEditableResource(type, resourceId, expectedVersion);
            PrivateFileStorage.Download upload;
            try {
                upload = files.openOwnedForEvidenceScan(fileId);
            } catch (IOException exception) {
                throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
            }
            ReviewEvidenceFileType fileType;
            EvidenceContentScanner.ScanResult scan;
            try (InputStream content = new BufferedInputStream(upload.stream())) {
                try {
                    fileType = ReviewEvidenceFileType.detect(content);
                } catch (IllegalArgumentException exception) {
                    throw new BusinessException(PlatformError.INVALID_ARGUMENT);
                }
                scan = scanner.scan(content);
            } catch (IOException exception) {
                throw new BusinessException(PlatformError.INTERNAL_ERROR);
            }
            if (!scan.clean() || upload.metadata().sizeBytes() <= 0 || upload.metadata().sizeBytes() > maxBytes) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            validatePreviewableFile(fileId, fileType, upload.metadata().sizeBytes(), scan.sha256());
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM platform_review_evidence
                    WHERE tenant_id = ? AND resource_type = ? AND resource_id = ? AND resource_version = ?
                      AND file_id = ?)
                    """, Boolean.class, resource.tenantId(), type.name(), resourceId, expectedVersion, fileId))) {
                throw new BusinessException(PlatformError.DATA_CONFLICT);
            }
            long evidenceId = ids.nextId();
            jdbc.update("""
                    INSERT INTO platform_review_evidence
                        (id, tenant_id, resource_type, resource_id, resource_version, file_id, category,
                         media_type, size_bytes, sha256, scan_status, scanner_code, scanned_at,
                         authenticity_status, source_code, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLEAN', 'CLAMAV', CURRENT_TIMESTAMP,
                            'NOT_VERIFIED', 'APPLICANT_UPLOAD', ?)
                    """, evidenceId, resource.tenantId(), type.name(), resourceId, expectedVersion, fileId,
                    category.name(), fileType.mediaType(), upload.metadata().sizeBytes(), scan.sha256(),
                    ExecutionContext.require().actorId());
            audit.record("platform:evidence:attached", "platform_review_evidence", Long.toString(evidenceId),
                    "SUCCESS", applicantAudit(resource, type, expectedVersion, "EVIDENCE_ATTACHED"));
            return jdbc.queryForObject("""
                    SELECT id, resource_version, category, created_at
                    FROM platform_review_evidence WHERE id = ? AND tenant_id = ?
                    """, (result, row) -> mapApplicantEvidence(result), evidenceId, resource.tenantId());
        } catch (BusinessException exception) {
            recordFailureAfterRollback("platform:evidence:attached", "review_resource", Long.toString(resourceId),
                    "DENIED", applicantAudit(resource, type, expectedVersion, attachReason(exception)));
            throw exception;
        } catch (RuntimeException exception) {
            recordFailureAfterRollback("platform:evidence:attached", "review_resource", Long.toString(resourceId),
                    "FAILED", applicantAudit(resource, type, expectedVersion, "EVIDENCE_BINDING_FAILED"));
            throw exception;
        }
    }

    /**
     * 按当前组织成员角色读取指定版本已关联材料的最小摘要。
     * @param type 组织或需求资源类型
     * @param resourceId 组织或需求编号
     * @param resourceVersion 被申请方查看的业务版本
     * @return 不含文件名、摘要、所有者或原件内容的材料摘要
     * @throws BusinessException 组织成员权限不足或资源不属于当前组织
     */
    @Transactional
    public List<ApplicantEvidenceView> listForApplicant(ReviewCaseType type, long resourceId, long resourceVersion) {
        requirePositive(resourceId, resourceVersion);
        if (type == null) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        ResourceSnapshot resource;
        try {
            resource = requireApplicantResource(type, resourceId);
        } catch (BusinessException exception) {
            recordFailureAfterRollback("platform:evidence:accessed", "review_resource", Long.toString(resourceId),
                    "DENIED", applicantAudit(null, type, resourceVersion, "APPLICANT_SCOPE_DENIED"));
            throw exception;
        } catch (RuntimeException exception) {
            recordFailureAfterRollback("platform:evidence:accessed", "review_resource", Long.toString(resourceId),
                    "FAILED", applicantAudit(null, type, resourceVersion, "APPLICANT_EVIDENCE_LIST_FAILED"));
            throw exception;
        }
        List<ApplicantEvidenceView> result = jdbc.query("""
                SELECT id, resource_version, category, created_at
                FROM platform_review_evidence
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ? AND resource_version = ?
                ORDER BY id
                """, (row, index) -> mapApplicantEvidence(row), resource.tenantId(), type.name(), resourceId,
                resourceVersion);
        audit.record("platform:evidence:accessed", "review_resource", Long.toString(resourceId), "SUCCESS",
                applicantAudit(resource, type, resourceVersion, "APPLICANT_EVIDENCE_LIST"));
        return List.copyOf(result);
    }

    /**
     * 读取本人有效认领任务对应版本的证据元数据。
     * @param taskId 岗位审核或独立复核任务编号
     * @return 仅含类别、类型、大小和独立真实性状态的材料摘要
     * @throws BusinessException 当前任务未由本人有效认领或证据范围无效
     */
    @Transactional
    public List<PlatformEvidenceView> listForTask(long taskId) {
        PlatformTaskService.EvidenceTaskScope task = requireTaskScope(taskId, "TASK_EVIDENCE_LIST");
        List<PlatformEvidenceView> result = jdbc.query("""
                SELECT id, resource_version, category, media_type, size_bytes, scan_status,
                       scanned_at, authenticity_status, source_code
                FROM platform_review_evidence
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ? AND resource_version = ?
                ORDER BY id
                """, (row, index) -> mapPlatformEvidence(row), task.tenantId(), task.resourceType(),
                task.resourceId(), task.resourceVersion());
        audit.record("platform:evidence:accessed", "platform_review_task", Long.toString(taskId), "SUCCESS",
                taskAudit(task, "EVIDENCE_LIST"));
        return List.copyOf(result);
    }

    /**
     * Reads the immutable verification trail for materials in the caller's claimed task.
     * @param taskId claimed review task identifier
     * @return verification records limited to the task's exact resource version
     * @throws BusinessException when task authorization or version checks fail
     */
    @Transactional
    public List<VerificationView> listVerifications(long taskId) {
        PlatformTaskService.EvidenceTaskScope task = requireTaskScope(taskId, "EVIDENCE_VERIFICATION_LIST");
        List<VerificationView> result = jdbc.query("""
                SELECT v.id, v.evidence_id, v.resource_version, v.dimension, v.result,
                       v.source_label, v.source_reference, v.basis, v.actor_id, v.created_at
                FROM platform_review_evidence_verification v
                WHERE v.tenant_id = ? AND v.review_case_id = ? AND v.task_id = ?
                  AND v.resource_type = ? AND v.resource_id = ? AND v.resource_version = ?
                ORDER BY v.created_at, v.id
                """, (row, index) -> mapVerification(row), task.tenantId(), task.reviewCaseId(), task.taskId(),
                task.resourceType(), task.resourceId(), task.resourceVersion());
        audit.record("platform:evidence:verification:read", "platform_review_task", Long.toString(taskId),
                "SUCCESS", taskAudit(task, "EVIDENCE_VERIFICATION_LIST"));
        return List.copyOf(result);
    }

    /**
     * Appends one itemized verification result without mutating the uploaded evidence row.
     * @param taskId claimed review task identifier
     * @param evidenceId material identifier within the task version
     * @param request verification dimension, result, source and basis
     * @return the appended immutable verification record
     * @throws BusinessException when the material is outside the task or request is invalid
     */
    @Transactional
    public VerificationView recordVerification(long taskId, long evidenceId, VerificationRequest request) {
        requirePositive(taskId, evidenceId);
        PlatformTaskService.EvidenceTaskScope task = requireTaskScope(taskId, "EVIDENCE_VERIFICATION_RECORD");
        EvidenceRow evidence = jdbc.query("""
                SELECT id, tenant_id, resource_type, resource_id, resource_version, file_id, media_type,
                       size_bytes, sha256, category, scan_status, scanned_at, authenticity_status, source_code
                FROM platform_review_evidence
                WHERE id = ? AND tenant_id = ? AND resource_type = ? AND resource_id = ?
                  AND resource_version = ?
                """, result -> result.next() ? mapEvidence(result) : null, evidenceId, task.tenantId(),
                task.resourceType(), task.resourceId(), task.resourceVersion());
        if (evidence == null) {
            recordFailureAfterRollback("platform:evidence:verification:recorded", "platform_review_evidence",
                    Long.toString(evidenceId), "DENIED", taskAudit(task, "EVIDENCE_NOT_IN_TASK_VERSION"));
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        String sourceLabel = requiredText(request.sourceLabel(), 200);
        String basis = requiredText(request.basis(), 2000);
        String sourceReference = optionalText(request.sourceReference(), 200);
        long id = ids.nextId();
        jdbc.update("""
                INSERT INTO platform_review_evidence_verification
                    (id, tenant_id, review_case_id, task_id, evidence_id, resource_type, resource_id,
                     resource_version, dimension, result, source_label, source_reference, basis, actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, task.tenantId(), task.reviewCaseId(), task.taskId(), evidence.id(), task.resourceType(),
                task.resourceId(), task.resourceVersion(), request.dimension().name(), request.result().name(),
                sourceLabel, sourceReference, basis, ExecutionContext.require().actorId());
        VerificationView view = jdbc.query("""
                SELECT id, evidence_id, resource_version, dimension, result,
                       source_label, source_reference, basis, actor_id, created_at
                FROM platform_review_evidence_verification WHERE id = ?
                """, result -> result.next() ? mapVerification(result) : null, id);
        audit.record("platform:evidence:verification:recorded", "platform_review_evidence",
                Long.toString(evidenceId), "SUCCESS", taskAudit(task, request.dimension().name()));
        return view;
    }

    /**
     * 逐次校验认领任务和材料绑定，校验扫描摘要后只返回去元数据的 PNG 预览。
     * @param taskId 当前平台岗位任务编号
     * @param evidenceId 已绑定材料编号
     * @param pageIndex PDF 零起始页码；图片仅支持零
     * @return 脱离原始格式和元数据的 PNG 字节
     * @throws BusinessException 任务、材料或当前访问身份不在授权范围
     * @throws IllegalStateException 私有文件与扫描摘要不一致或预览无法安全生成
     */
    @Transactional
    public byte[] preview(long taskId, long evidenceId, int pageIndex) {
        requirePositive(taskId, evidenceId);
        PlatformTaskService.EvidenceTaskScope task = requireTaskScope(taskId, "TASK_EVIDENCE_PREVIEW");
        EvidenceRow evidence = jdbc.query("""
                SELECT id, tenant_id, resource_type, resource_id, resource_version, file_id, media_type,
                       size_bytes, sha256, category, scan_status, scanned_at, authenticity_status, source_code
                FROM platform_review_evidence
                WHERE id = ? AND tenant_id = ? AND resource_type = ? AND resource_id = ? AND resource_version = ?
                  AND scan_status = 'CLEAN'
                """, result -> result.next() ? mapEvidence(result) : null, evidenceId, task.tenantId(),
                task.resourceType(), task.resourceId(), task.resourceVersion());
        if (evidence == null) {
            recordFailureAfterRollback("platform:evidence:accessed", "platform_review_task",
                    Long.toString(taskId), "DENIED", taskAudit(task, "EVIDENCE_NOT_IN_TASK_VERSION"));
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        PlatformFileAccessScope scope = new PlatformFileAccessScope(evidence.id(), task.taskId(), task.reviewCaseId(),
                task.tenantId(), task.resourceType(), task.resourceId(), task.resourceVersion(), task.taskType(),
                task.taskType());
        try {
            PrivateFileStorage.Download original = files.openForPlatform(evidence.fileId(), scope);
            byte[] source;
            try (InputStream input = original.stream()) {
                source = input.readNBytes(Math.toIntExact(maxBytes + 1));
            } catch (IOException exception) {
                throw new IllegalStateException("Evidence preview is unavailable");
            }
            if (source.length > maxBytes || source.length != evidence.sizeBytes()
                    || !evidence.sha256().equals(sha256(source))) {
                throw new IllegalStateException("Evidence preview integrity check failed");
            }
            ReviewEvidenceFileType type = ReviewEvidenceFileType.fromMediaType(evidence.mediaType());
            byte[] preview = renderer.render(source, type, pageIndex);
            audit.record("platform:evidence:accessed", "platform_review_evidence", Long.toString(evidenceId),
                    "SUCCESS", taskAudit(task, "EVIDENCE_PREVIEW"));
            return preview;
        } catch (BusinessException exception) {
            recordFailureAfterRollback("platform:evidence:accessed", "platform_review_evidence",
                    Long.toString(evidenceId),
                    "DENIED", taskAudit(task, "EVIDENCE_ACCESS_DENIED"));
            throw exception;
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            recordFailureAfterRollback("platform:evidence:accessed", "platform_review_evidence",
                    Long.toString(evidenceId),
                    "FAILED", taskAudit(task, "EVIDENCE_PREVIEW_FAILED"));
            throw new IllegalStateException("Evidence preview is unavailable");
        }
    }

    /**
     * 读取当前任务所绑定事项和版本的最小不可变访问范围；授权拒绝本身也要单独留痕。
     * @param taskId 任务编号
     * @param reasonCode 成功/拒绝事件的固定用途码
     * @return 当前任务认领范围
     */
    private PlatformTaskService.EvidenceTaskScope requireTaskScope(long taskId, String reasonCode) {
        requirePositive(taskId);
        try {
            return tasks.requireEvidenceTask(taskId);
        } catch (RuntimeException exception) {
            if (ExecutionContext.isPresent()) {
                recordFailureAfterRollback("platform:evidence:accessed", "platform_review_task",
                        Long.toString(taskId), "DENIED",
                        new AuditRecorder.AuditDetails(null, null, null, Long.toString(taskId), null,
                                ExecutionContext.require().actorRole(), "TASK_SCOPE_DENIED", reasonCode));
            }
            throw exception;
        }
    }

    /**
     * 读取可编辑的当前资源并按业务角色检查申请方身份。
     * @param type 资源种类
     * @param resourceId 业务资源编号
     * @param expectedVersion 客户端正在编辑的版本
     * @return 锁定至材料绑定事务结束的当前资源快照
     * @throws BusinessException 成员角色不足、资源不存在、状态不允许或版本冲突
     */
    private ResourceSnapshot requireEditableResource(ReviewCaseType type, long resourceId, long expectedVersion) {
        ResourceSnapshot resource = requireApplicantResource(type, resourceId);
        String status;
        long version;
        if (type == ReviewCaseType.ORGANIZATION) {
            ResourceState organization = jdbc.query("""
                    SELECT status, application_version FROM business_organization
                    WHERE id = ? AND tenant_id = ? FOR UPDATE
                    """, result -> result.next() ? new ResourceState(result.getString("status"),
                    result.getLong("application_version")) : null,
                    resourceId, resource.tenantId());
            if (organization == null) {
                throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
            }
            status = organization.status();
            version = organization.version();
        } else {
            DemandSnapshot demand = jdbc.query("""
                    SELECT d.status, d.current_revision AS resource_version
                    FROM business_demand d JOIN business_organization o
                      ON o.id = d.organization_id AND o.tenant_id = d.tenant_id
                    WHERE d.id = ? AND d.tenant_id = ? AND o.organization_role = 'EMPLOYER'
                    FOR UPDATE OF d
                    """, result -> result.next() ? new DemandSnapshot(result.getString("status"),
                    result.getLong("resource_version")) : null, resourceId, resource.tenantId());
            if (demand == null) {
                throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
            }
            status = demand.status();
            version = demand.version();
        }
        if (!List.of("DRAFT", "NEEDS_CORRECTION").contains(status)) {
            throw new BusinessException(PlatformError.DATA_CONFLICT);
        }
        if (version != expectedVersion) {
            throw new BusinessException(PlatformError.VERSION_CONFLICT);
        }
        Long caseId = jdbc.query("""
                SELECT id FROM platform_review_case
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?
                """, result -> result.next() ? result.getLong(1) : null,
                resource.tenantId(), type.name(), resourceId);
        return new ResourceSnapshot(resource.tenantId(), caseId, version);
    }

    /**
     * 验证当前组织成员和对应申请资源的编辑权限。
     * @param type 资源种类
     * @param resourceId 组织或需求编号
     * @return 所属租户和事项编号；草稿尚无事项时事项编号为空
     * @throws BusinessException 资源不属于当前租户或成员权限不足
     */
    private ResourceSnapshot requireApplicantResource(ReviewCaseType type, long resourceId) {
        if (type == null || resourceId <= 0) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        long tenantId = ExecutionContext.requireTenant();
        if (type == ReviewCaseType.ORGANIZATION) {
            ExecutionContext.checkPermission("organization:organizations:write");
            organizations.membership(tenantId, Set.of(MembershipRole.ORGANIZATION_ADMIN));
            Long exists = jdbc.query("""
                    SELECT id FROM business_organization WHERE id = ? AND tenant_id = ?
                    """, result -> result.next() ? result.getLong(1) : null, resourceId, tenantId);
            if (exists == null) {
                throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
            }
        } else {
            ExecutionContext.checkPermission("organization:demands:write");
            OrganizationAuthorization.Membership membership = organizations.membership(tenantId,
                    OrganizationAuthorization.Membership.adminOr(MembershipRole.DEMAND_EDITOR));
            if (membership.organizationRole() != OrganizationRole.EMPLOYER) {
                throw new BusinessException(PlatformError.ACCESS_DENIED);
            }
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM business_demand
                    WHERE id = ? AND tenant_id = ? AND organization_id = ?)
                    """, Boolean.class, resourceId, tenantId, tenantId);
            if (!Boolean.TRUE.equals(exists)) {
                throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
            }
        }
        Long caseId = jdbc.query("""
                SELECT id FROM platform_review_case
                WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?
                """, result -> result.next() ? result.getLong(1) : null, tenantId, type.name(), resourceId);
        return new ResourceSnapshot(tenantId, caseId, 1L);
    }

    /** 在绑定前验证文件能被受限预览解码，并确认二次读取内容与扫描摘要一致。
     * @param fileId 本人上传的私有文件标识
     * @param fileType 从文件魔数识别的有限类型
     * @param expectedSize 上传元数据中的字节数
     * @param expectedDigest 扫描器处理字节的 SHA-256
     * @throws BusinessException 文件在扫描后变化、结构伪造或不能安全栅格化
     */
    private void validatePreviewableFile(String fileId, ReviewEvidenceFileType fileType,
                                         long expectedSize, String expectedDigest) {
        try {
            PrivateFileStorage.Download download = files.openOwnedForEvidenceScan(fileId);
            byte[] source;
            try (InputStream input = download.stream()) {
                source = input.readNBytes(Math.toIntExact(maxBytes + 1));
            }
            if (source.length > maxBytes || source.length != expectedSize || !expectedDigest.equals(sha256(source))) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            renderer.render(source, fileType, 0);
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
    }

    /**
     * 创建只包含事项、版本、岗位动作和固定用途码的申请方材料审计关联。
     * @param resource 资源快照
     * @param type 资源类型
     * @param version 访问的资源版本
     * @param reasonCode 固定审计原因码
     * @return 脱敏审计关联
     */
    private AuditRecorder.AuditDetails applicantAudit(ResourceSnapshot resource, ReviewCaseType type,
                                                       long version, String reasonCode) {
        return new AuditRecorder.AuditDetails(resource == null || resource.caseId() == null
                ? null : Long.toString(resource.caseId()), null, null, null, version, null,
                reasonCode, "APPLICANT_" + type.name());
    }

    /**
     * 为拒绝绑定选择不包含文件名或扫描器详细特征的稳定原因码。
     * @param exception 业务拒绝
     * @return 固定审计原因码
     */
    private String attachReason(BusinessException exception) {
        return switch (exception.getErrorKey()) {
            case "COMMON.DATA_CONFLICT" -> "EVIDENCE_ALREADY_ATTACHED";
            case "COMMON.VERSION_CONFLICT" -> "RESOURCE_VERSION_STALE";
            case "SECURITY.ACCESS_DENIED" -> "APPLICANT_SCOPE_DENIED";
            case "COMMON.INVALID_ARGUMENT" -> "EVIDENCE_SCAN_OR_TYPE_REJECTED";
            default -> "EVIDENCE_BINDING_REJECTED";
        };
    }

    /**
     * 创建平台任务材料访问的统一审计关联。
     * @param task 已验证认领范围
     * @param reasonCode 固定动作结果码
     * @return 仅含事项、任务、版本、岗位和用途的审计关联
     */
    private AuditRecorder.AuditDetails taskAudit(PlatformTaskService.EvidenceTaskScope task, String reasonCode) {
        return new AuditRecorder.AuditDetails(Long.toString(task.reviewCaseId()), null, null,
                Long.toString(task.taskId()), task.resourceVersion(), ExecutionContext.require().actorRole(),
                reasonCode, task.taskType());
    }

    /**
     * 映射申请方可见的材料引用，不返回文件名、摘要、所有者或原件存储位置。
     * @param result SQL 结果行
     * @return 最小材料引用
     * @throws SQLException 读取列失败
     */
    private ApplicantEvidenceView mapApplicantEvidence(ResultSet result) throws SQLException {
        return new ApplicantEvidenceView(Long.toString(result.getLong("id")), result.getLong("resource_version"),
                ReviewEvidenceCategory.valueOf(result.getString("category")),
                result.getTimestamp("created_at").toInstant());
    }

    /**
     * 映射平台审核员可见的扫描及真实性分离状态，不返回文件摘要或所有者。
     * @param result SQL 结果行
     * @return 最小平台证据摘要
     * @throws SQLException 读取列失败
     */
    private PlatformEvidenceView mapPlatformEvidence(ResultSet result) throws SQLException {
        return new PlatformEvidenceView(Long.toString(result.getLong("id")), result.getLong("resource_version"),
                ReviewEvidenceCategory.valueOf(result.getString("category")), result.getString("media_type"),
                result.getLong("size_bytes"), result.getString("scan_status"),
                result.getTimestamp("scanned_at").toInstant(), result.getString("authenticity_status"),
                result.getString("source_code"));
    }

    /** 将数据库逐项核验记录映射为接口响应。 */
    private VerificationView mapVerification(ResultSet result) throws SQLException {
        return new VerificationView(Long.toString(result.getLong("id")),
                Long.toString(result.getLong("evidence_id")), result.getLong("resource_version"),
                VerificationDimension.valueOf(result.getString("dimension")),
                VerificationResult.valueOf(result.getString("result")), result.getString("source_label"),
                result.getString("source_reference"), result.getString("basis"),
                Long.toString(result.getLong("actor_id")), result.getTimestamp("created_at").toInstant());
    }

    /** 规范化逐项核验自由文本并限制其长度，防止把敏感原件写入审计摘要。 */
    private String requiredText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return normalized;
    }

    /** 将可选核验来源引用规范化为空值。 */
    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(PlatformError.INVALID_ARGUMENT);
        }
        return normalized;
    }

    /**
     * 映射仅在服务端用于预览的证据绑定行。
     * @param result SQL 结果行
     * @return 私有文件引用及扫描摘要
     * @throws SQLException 读取列失败
     */
    private EvidenceRow mapEvidence(ResultSet result) throws SQLException {
        return new EvidenceRow(result.getLong("id"), result.getLong("tenant_id"),
                result.getString("resource_type"), result.getLong("resource_id"),
                result.getLong("resource_version"), result.getString("file_id"), result.getString("media_type"),
                result.getLong("size_bytes"), result.getString("sha256").trim(),
                ReviewEvidenceCategory.valueOf(result.getString("category")), result.getString("scan_status"),
                result.getTimestamp("scanned_at").toInstant(), result.getString("authenticity_status"),
                result.getString("source_code"));
    }

    /**
     * 计算预览字节的完整摘要，以验证栅格化前文件仍与扫描时一致。
     * @param content 已从授权私有存储读取的文件
     * @return 小写 SHA-256 摘要
     * @throws IllegalStateException 运行时缺少标准摘要算法
     */
    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 校验资源编号和版本均为正数。
     * @param values 待验证的资源编号或版本
     * @throws BusinessException 任一编号无效
     */
    private void requirePositive(long... values) {
        for (long value : values) {
            if (value <= 0) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
        }
    }

    /** 等当前材料事务回滚并释放审计链锁后，再独立提交拒绝或失败事件。
     * @param action 固定审计动作
     * @param resourceType 资源类型代码
     * @param resourceId 非敏感资源编号
     * @param outcome DENIED 或 FAILED
     * @param details 事项、版本、任务和用途关联
     */
    private void recordFailureAfterRollback(String action, String resourceType, String resourceId,
                                            String outcome, AuditRecorder.AuditDetails details) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            audit.record(action, resourceType, resourceId, outcome, details);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 等待当前事务释放其可能已持有的审计链锁后独立写入失败事件。
             * @param status 当前材料事务的最终状态
             */
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    audit.record(action, resourceType, resourceId, outcome, details);
                }
            }
        });
    }

    /** 申请方可查看的材料引用摘要。 */
    public record ApplicantEvidenceView(String evidenceId, long resourceVersion,
                                        ReviewEvidenceCategory category, Instant createdAt) {
        /**
         * @param evidenceId 稳定材料引用编号
         * @param resourceVersion 材料绑定版本
         * @param category 申请方提交时声明的粗粒度类别
         * @param createdAt 服务端绑定时间
         */
    }

    /** 平台审核员可见的最小材料与安全扫描摘要。 */
    public record PlatformEvidenceView(String evidenceId, long resourceVersion, ReviewEvidenceCategory category,
                                       String mediaType, long sizeBytes, String scanStatus, Instant scannedAt,
                                       String authenticityStatus, String sourceCode) {
        /**
         * @param evidenceId 稳定材料引用编号
         * @param resourceVersion 被审核资源版本
         * @param category 申请方声明的材料类别，不代表核验结论
         * @param mediaType 按内容魔数识别的允许预览类型
         * @param sizeBytes 私有文件实际长度
         * @param scanStatus 恶意内容扫描状态
         * @param scannedAt 扫描通过时间
         * @param authenticityStatus 独立真实性状态，初始为 NOT_VERIFIED
         * @param sourceCode 已知上传来源代码
         */
    }

    /** 逐项核验维度；真实性与安全扫描始终保持独立。 */
    public enum VerificationDimension {
        /** 材料是否齐全。 */
        COMPLETENESS,
        /** 材料内容真实性及来源是否可确认。 */
        AUTHENTICITY,
        /** 材料是否仍在有效期或适用范围内。 */
        VALIDITY
    }

    /** 逐项核验结论；NOT_VERIFIED 保留外部渠道不可用或证据不足状态。 */
    public enum VerificationResult {
        /** 材料完整。 */
        COMPLETE,
        /** 材料缺失或内容不足。 */
        MISSING,
        /** 来源和内容已按记录依据核实。 */
        VERIFIED,
        /** 尚不能确认真实性或有效性。 */
        NOT_VERIFIED,
        /** 存在待解释冲突。 */
        CONFLICT,
        /** 材料仍在有效期或适用范围。 */
        VALID,
        /** 材料已过期。 */
        EXPIRED,
        /** 当前材料不适用该维度。 */
        NOT_APPLICABLE
    }

    /** 逐项核验提交请求。 */
    public record VerificationRequest(VerificationDimension dimension, VerificationResult result,
                                      String sourceLabel, String sourceReference, String basis) {
        /**
         * @param dimension 核验维度
         * @param result 与维度匹配的核验结果
         * @param sourceLabel 实际使用的核验来源名称
         * @param sourceReference 来源检索标识或材料版本引用
         * @param basis 可供下一处理人复核的事实依据
         */
        public VerificationRequest {
            if (dimension == null || result == null) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
            boolean valid = switch (dimension) {
                case COMPLETENESS -> result == VerificationResult.COMPLETE || result == VerificationResult.MISSING;
                case AUTHENTICITY -> result == VerificationResult.VERIFIED
                        || result == VerificationResult.NOT_VERIFIED || result == VerificationResult.CONFLICT;
                case VALIDITY -> result == VerificationResult.VALID || result == VerificationResult.EXPIRED
                        || result == VerificationResult.NOT_APPLICABLE || result == VerificationResult.NOT_VERIFIED;
            };
            if (!valid) {
                throw new BusinessException(PlatformError.INVALID_ARGUMENT);
            }
        }
    }

    /** 仅返回下一位审核人员继续判断所需的逐项核验字段。 */
    public record VerificationView(String verificationId, String evidenceId, long resourceVersion,
                                   VerificationDimension dimension, VerificationResult result,
                                   String sourceLabel, String sourceReference, String basis,
                                   String actorId, Instant createdAt) {
        /**
         * @param verificationId 追加核验记录编号
         * @param evidenceId 对应材料编号
         * @param resourceVersion 材料所属业务版本
         * @param dimension 核验维度
         * @param result 核验结论
         * @param sourceLabel 来源名称
         * @param sourceReference 来源检索标识
         * @param basis 可复核的事实依据
         * @param actorId 记录核验的内部账号编号
         * @param createdAt 服务端记录时间
         */
    }

    /** 内部可编辑资源快照及可空审核事项编号。 */
    private record ResourceSnapshot(long tenantId, Long caseId, long version) {
        /**
         * @param tenantId 数据隔离组织编号
         * @param caseId 已提交申请的事项编号；首次提交前为空
         * @param version 当前申请版本
         */
    }

    /** 需求审核态和当前修订号的内部查询结果。 */
    private record DemandSnapshot(String status, long version) {
        /**
         * @param status 当前需求状态
         * @param version 当前需求修订号
         */
    }

    /** 组织申请状态和应用资料版本的锁定快照。 */
    private record ResourceState(String status, long version) {
        /**
         * @param status 当前组织申请状态
         * @param version 当前组织申请版本
         */
    }

    /** 仅供校验文件绑定、扫描摘要和任务访问范围使用的内部数据行。 */
    private record EvidenceRow(long id, long tenantId, String resourceType, long resourceId, long resourceVersion,
                               String fileId, String mediaType, long sizeBytes, String sha256,
                               ReviewEvidenceCategory category, String scanStatus, Instant scannedAt,
                               String authenticityStatus, String sourceCode) {
        /**
         * @param id 材料引用编号
         * @param tenantId 私有文件所属组织
         * @param resourceType 组织或需求对象类型
         * @param resourceId 业务对象编号
         * @param resourceVersion 业务版本
         * @param fileId 私有存储编号
         * @param mediaType 扫描前识别的文件类型
         * @param sizeBytes 扫描文件大小
         * @param sha256 扫描时计算的内容摘要
         * @param category 申请方声明的材料类别
         * @param scanStatus 恶意内容扫描结果
         * @param scannedAt 扫描时间
         * @param authenticityStatus 独立真实性状态
         * @param sourceCode 受控上传来源
         */
    }
}
