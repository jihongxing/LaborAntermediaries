package com.company.labor.modules.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 平台监察查询、覆盖目录和监察发现 API 的输入输出类型。 */
public final class PlatformAuditDtos {
    /** 仅承载 API 类型的命名空间禁止实例化。 */
    private PlatformAuditDtos() {
    }

    /** 跨组织审计查询结果；游标使用最后一条事件编号。 */
    public record QueryResult(
            /** 结构化查询记录编号。 */
            String queryId,
            /** 按编号倒序返回的审计事件。 */
            List<AuditEvent> events,
            /** 下一页游标；没有下一页时为空。 */
            String nextCursor) {
    }

    /** 可供监察员筛选和复核的最小审计事件字段。 */
    public record AuditEvent(
            /** 审计事件编号。 */
            String eventId,
            /** 操作者账号编号；匿名事件为空。 */
            String actorId,
            /** PERSON、PLATFORM、SERVICE 或 ANONYMOUS。 */
            String actorType,
            /** 事件归属组织编号；跨组织平台事件为空。 */
            String organizationId,
            /** 固定动作码。 */
            String action,
            /** 资源类型。 */
            String resourceType,
            /** 资源编号。 */
            String resourceId,
            /** SUCCESS、DENIED、FAILED 或 CONFLICT。 */
            String outcome,
            /** 关联事项编号。 */
            String reviewCaseId,
            /** 关联业务版本。 */
            Long resourceVersion,
            /** 事件发生时的平台岗位。 */
            String actorRole,
            /** 结构化原因码。 */
            String reasonCode,
            /** 访问用途码。 */
            String purposeCode,
            /** 服务端记录时间。 */
            Instant occurredAt) {
    }

    /** 一个审计目录项及其最近实际事件；无事件时保持时间和延迟为空。 */
    public record CoverageItem(
            /** 事件目录编码。 */
            String eventCode,
            /** 所属模块。 */
            String moduleCode,
            /** 目录说明。 */
            String description,
            /** NOT_IMPLEMENTED、PENDING、PARTIAL、CONNECTED 或 DEGRADED。 */
            String status,
            /** 最近事件编号。 */
            String lastEventId,
            /** 最近事件时间。 */
            Instant lastEventAt,
            /** 可测量的事件延迟秒数；没有测量来源时为空。 */
            Long latencySeconds) {
    }

    /** 监察发现的摘要，不包含关联材料原件。 */
    public record FindingSummary(
            /** 独立监察发现编号。 */
            String findingId,
            /** 关联审核事项编号。 */
            String reviewCaseId,
            /** 关联业务资源类型。 */
            String resourceType,
            /** 关联业务资源编号。 */
            String resourceId,
            /** 关联业务版本。 */
            Long resourceVersion,
            /** 发现标题。 */
            String title,
            /** UNCONFIRMED、SUPPORTED 或 NOT_SUPPORTED。 */
            String factStatus,
            /** 当前独立发现工作流状态。 */
            String workflowStatus,
            /** 创建人账号编号。 */
            String createdBy,
            /** 创建时间。 */
            Instant createdAt,
            /** 最近更新时间。 */
            Instant updatedAt) {
    }

    /** 监察发现详情及其追加式回应、复核和认领记录。 */
    public record FindingDetail(
            /** 发现摘要。 */
            FindingSummary summary,
            /** 追加式处理记录。 */
            List<FindingEntry> entries,
            /** 当前阶段的有效认领；无认领时为空。 */
            FindingClaim activeClaim) {
    }

    /** 发现创建、回应、复核或关闭时追加的一条不可变记录。 */
    public record FindingEntry(
            /** 记录编号。 */
            String entryId,
            /** CREATED、RESPONSE、REVIEW 或 CLOSED。 */
            String entryType,
            /** 操作者账号编号。 */
            String actorId,
            /** 追加内容。 */
            String content,
            /** 本条复核记录的事实结论。 */
            String factStatus,
            /** 服务端追加时间。 */
            Instant createdAt) {
    }

    /** 发现处理阶段的一次认领摘要。 */
    public record FindingClaim(
            /** 认领编号。 */
            String claimId,
            /** RESPONSE 或 REVIEW。 */
            String stage,
            /** 认领账号编号。 */
            String actorId,
            /** ACTIVE、COMPLETED、RELEASED 或 RECUSED。 */
            String status,
            /** 认领开始时间。 */
            Instant claimedAt,
            /** 关闭时间。 */
            Instant closedAt,
            /** 关闭原因。 */
            String closeReason,
            /** 当前是否仍有效。 */
            boolean active) {
    }

    /** 创建监察发现时提交的受控关联和事实描述。 */
    public record CreateFindingRequest(
            /** 必须存在的审核事项编号。 */
            @NotBlank String reviewCaseId,
            /** 可选的业务版本编号。 */
            String resourceVersion,
            /** 可选的事项内审计事件编号。 */
            String eventId,
            /** 可选的事项版本材料编号。 */
            String evidenceId,
            /** 发现标题。 */
            @NotBlank @Size(max = 200) String title,
            /** 发现事实描述。 */
            @NotBlank @Size(max = 4000) String description) {
    }

    /** 认领回应或复核阶段时提交的岗位阶段。 */
    public record ClaimRequest(
            /** RESPONSE 或 REVIEW。 */
            @NotNull Stage stage) {
    }

    /** 监察发现回应内容。 */
    public record ResponseRequest(
            /** 对发现的事实回应或补充材料说明。 */
            @NotBlank @Size(max = 4000) String content) {
    }

    /** 监察发现复核内容及事实状态。 */
    public record ReviewRequest(
            /** 支持或不支持该发现的事实结论。 */
            @NotNull FactStatus factStatus,
            /** 复核依据和结论说明。 */
            @NotBlank @Size(max = 4000) String content) {
    }

    /** 发现处理阶段。 */
    public enum Stage {
        /** 等待被监察对象回应。 */
        RESPONSE,
        /** 等待独立人员复核。 */
        REVIEW
    }

    /** 复核后的事实状态。 */
    public enum FactStatus {
        /** 事实得到支持。 */
        SUPPORTED,
        /** 事实未得到支持。 */
        NOT_SUPPORTED
    }
}
