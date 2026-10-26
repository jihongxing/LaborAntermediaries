package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.platform.PlatformTaskDtos.AppealReviewRequest;
import com.company.labor.modules.platform.PlatformTaskDtos.ReasonRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供平台岗位审核任务队列、原子认领、回避、释放、恢复及独立复核 API。 */
@RestController
@RequestMapping("/platform/tasks")
public class PlatformTaskController {
    /** 每个读写操作均在领域服务内重新检查实时平台授权和当前认领。 */
    private final PlatformTaskService tasks;

    /**
     * 创建平台任务接口控制器。
     * @param tasks 负责授权、版本、认领和审计校验的任务服务
     */
    public PlatformTaskController(PlatformTaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * 读取指定岗位的最小任务摘要；不会暴露未认领详情、申请材料或认领人身份。
     * @param taskType 任务种类代码
     * @return 当前岗位可见的任务队列
     */
    @GetMapping
    public Result<List<PlatformTaskService.QueueItem>> queue(@RequestParam String taskType) {
        return Result.success(tasks.queue(PlatformTaskType.fromCode(taskType)));
    }

    /**
     * 以独立恢复权限查看当前占用任务的最小摘要。
     * @return 仅含任务编号、岗位和认领时间的恢复队列
     */
    @GetMapping("/recovery")
    public Result<List<PlatformTaskService.RecoveryItem>> recoveryQueue() {
        return Result.success(tasks.recoveryQueue());
    }

    /**
     * 读取当前认证账号的异常恢复通知，平台岗位撤销不影响本人读取。
     * @return 只属于当前账号的最小通知列表
     */
    @GetMapping("/recovery-notices")
    public Result<List<PlatformTaskService.RecoveryNotice>> recoveryNotices() {
        return Result.success(tasks.recoveryNotices());
    }

    /**
     * 原子认领本岗位任务；重放同一幂等键返回原认领结果。
     * @param taskId 岗位任务编号
     * @param idempotencyKey 本次请求的幂等键
     * @return 稳定认领编号和当前认领状态
     */
    @PostMapping("/{taskId}/claim")
    public Result<PlatformTaskService.ClaimResult> claim(@PathVariable long taskId,
                                                          @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.success(tasks.claim(taskId, idempotencyKey));
    }

    /**
     * 读取本人有效认领任务的事项历史；申请详情与材料仍由后续门禁按范围开放。
     * @param taskId 本人当前认领的岗位任务
     * @return 已授权任务的事项版本、处理历史及独立复核摘要
     */
    @GetMapping("/{taskId}")
    public Result<PlatformTaskService.TaskDetails> details(@PathVariable long taskId) {
        return Result.success(tasks.details(taskId));
    }

    /**
     * 由本人说明理由后释放当前任务，使其回到原岗位队列。
     * @param taskId 本人当前认领的任务
     * @param request 释放理由
     * @return 回到岗位队列的任务编号
     */
    @PostMapping("/{taskId}/release")
    public Result<String> release(@PathVariable long taskId, @Valid @RequestBody ReasonRequest request) {
        return Result.success(tasks.release(taskId, request.reason()));
    }

    /**
     * 记录本人回避并立即撤销其当前任务访问。
     * @param taskId 涉及的岗位任务
     * @param request 回避理由
     * @return 回到岗位队列的任务编号
     */
    @PostMapping("/{taskId}/recusals")
    public Result<String> declareRecusal(@PathVariable long taskId, @Valid @RequestBody ReasonRequest request) {
        return Result.success(tasks.declareRecusal(taskId, request.reason()));
    }

    /**
     * 以独立恢复权限解除异常占用，不授予任务详情或处理权。
     * @param taskId 异常占用任务编号
     * @param request 恢复原因
     * @return 回到原岗位队列的任务编号
     */
    @PostMapping("/{taskId}/recovery")
    public Result<String> recover(@PathVariable long taskId, @Valid @RequestBody ReasonRequest request) {
        return Result.success(tasks.recover(taskId, request.reason()));
    }

    /**
     * 由有效独立复核认领人记录意见；意见不会自动改变原业务决定。
     * @param taskId 已认领的独立复核任务
     * @param request 复核意见和说明
     * @return 已追加的复核结果
     */
    @PostMapping("/{taskId}/appeal-review")
    public Result<PlatformTaskService.AppealReviewResult> completeAppealReview(
            @PathVariable long taskId, @Valid @RequestBody AppealReviewRequest request) {
        return Result.success(tasks.completeAppealReview(taskId, request.outcome(), request.reason()));
    }
}
