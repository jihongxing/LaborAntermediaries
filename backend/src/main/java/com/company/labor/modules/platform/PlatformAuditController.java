package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.platform.PlatformAuditDtos.ClaimRequest;
import com.company.labor.modules.platform.PlatformAuditDtos.CoverageItem;
import com.company.labor.modules.platform.PlatformAuditDtos.CreateFindingRequest;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingClaim;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingDetail;
import com.company.labor.modules.platform.PlatformAuditDtos.FindingSummary;
import com.company.labor.modules.platform.PlatformAuditDtos.QueryResult;
import com.company.labor.modules.platform.PlatformAuditDtos.ResponseRequest;
import com.company.labor.modules.platform.PlatformAuditDtos.ReviewRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 提供独立监察岗位使用的跨组织查询、覆盖目录和发现闭环 API。 */
@RestController
@RequestMapping("/platform/audit")
public class PlatformAuditController {
    /** 监察查询和发现工作流服务。 */
    private final PlatformAuditService audit;

    /**
     * 创建监察 API 控制器。
     * @param audit 监察领域服务
     */
    public PlatformAuditController(PlatformAuditService audit) {
        this.audit = audit;
    }

    /**
     * 按结构化过滤器查询跨组织审计事件。
     * @param organizationId 组织编号
     * @param reviewCaseId 审核事项编号
     * @param resourceId 资源编号
     * @param resourceVersion 业务版本
     * @param eventActorId 事件操作者编号
     * @param actorRole 事件发生时的平台岗位
     * @param action 固定动作码
     * @param outcome 事件结果
     * @param fromAt 起始时间
     * @param toAt 结束时间
     * @param beforeId 上一页游标
     * @param limit 页大小
     * @param purposeCode 访问用途码
     * @return 事件和下一页游标
     */
    @GetMapping
    public Result<QueryResult> query(@RequestParam(required = false) Long organizationId,
                                     @RequestParam(required = false) String reviewCaseId,
                                     @RequestParam(required = false) String resourceId,
                                     @RequestParam(required = false) Long resourceVersion,
                                     @RequestParam(required = false) Long eventActorId,
                                     @RequestParam(required = false) String actorRole,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String outcome,
                                     @RequestParam(required = false) Instant fromAt,
                                     @RequestParam(required = false) Instant toAt,
                                     @RequestParam(required = false) String beforeId,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "AUDIT_REVIEW") String purposeCode) {
        return Result.success(audit.query(organizationId, reviewCaseId, resourceId, resourceVersion, eventActorId,
                actorRole, action, outcome, fromAt, toAt, beforeId, limit, purposeCode));
    }

    /**
     * 读取全模块审计覆盖目录。
     * @return 目录状态和最近实际事件
     */
    @GetMapping("/coverage")
    public Result<List<CoverageItem>> coverage() {
        return Result.success(audit.coverage());
    }

    /**
     * 读取事项的完整跨组织监察历史。
     * @param reviewCaseId 稳定事项编号
     * @return 内部事项历史
     */
    @GetMapping("/requests/{reviewCaseId}")
    public Result<ReviewCaseHistoryDtos.View> history(@PathVariable long reviewCaseId) {
        return Result.success(audit.history(reviewCaseId));
    }

    /**
     * 读取监察发现队列。
     * @param workflowStatus 可选工作流状态
     * @return 发现摘要列表
     */
    @GetMapping("/findings")
    public Result<List<FindingSummary>> findings(@RequestParam(required = false) String workflowStatus) {
        return Result.success(audit.findings(workflowStatus));
    }

    /**
     * 追加一条事实状态默认为未确认的监察发现。
     * @param request 受控事项关联和发现内容
     * @return 新发现摘要
     */
    @PostMapping("/findings")
    public Result<FindingSummary> createFinding(@Valid @RequestBody CreateFindingRequest request) {
        return Result.success(audit.createFinding(request));
    }

    /**
     * 读取单一发现及其追加式处理记录。
     * @param findingId 发现编号
     * @return 发现详情
     */
    @GetMapping("/findings/{findingId}")
    public Result<FindingDetail> finding(@PathVariable long findingId) {
        return Result.success(audit.finding(findingId));
    }

    /**
     * 原子认领发现回应或独立复核阶段。
     * @param findingId 发现编号
     * @param request 认领阶段
     * @param idempotencyKey 本次认领幂等键
     * @return 认领结果
     */
    @PostMapping("/findings/{findingId}/claim")
    public Result<FindingClaim> claim(@PathVariable long findingId, @Valid @RequestBody ClaimRequest request,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.success(audit.claimFinding(findingId, request.stage(), idempotencyKey));
    }

    /**
     * 追加发现回应并推进到独立复核队列。
     * @param findingId 发现编号
     * @param request 回应内容
     * @return 更新后的发现详情
     */
    @PostMapping("/findings/{findingId}/response")
    public Result<FindingDetail> response(@PathVariable long findingId,
                                          @Valid @RequestBody ResponseRequest request) {
        return Result.success(audit.respond(findingId, request));
    }

    /**
     * 追加独立复核和关闭记录。
     * @param findingId 发现编号
     * @param request 复核事实状态和依据
     * @return 关闭后的发现详情
     */
    @PostMapping("/findings/{findingId}/review")
    public Result<FindingDetail> review(@PathVariable long findingId,
                                        @Valid @RequestBody ReviewRequest request) {
        return Result.success(audit.review(findingId, request));
    }
}
