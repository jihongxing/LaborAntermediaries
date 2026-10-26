package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.platform.PlatformTaskDtos.ReasonRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 提供申请方创建独立复核和请求审核员回避的受控业务入口。 */
@RestController
public class ReviewCaseActionController {
    /** 校验申请方组织关系、任务关系和独立复核资格的服务端业务服务。 */
    private final PlatformTaskService tasks;

    /**
     * 创建事项操作控制器。
     * @param tasks 任务及独立复核领域服务
     */
    public ReviewCaseActionController(PlatformTaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * 由原事项申请方申请对当前版本的审核决定进行独立复核。
     * @param decisionId 要复核的原审核决定编号
     * @param request 申请方理由
     * @return 新独立复核事项及其岗位任务编号
     */
    @PostMapping("/review-decisions/{decisionId}/appeals")
    public Result<PlatformTaskService.AppealCreated> createAppeal(@PathVariable long decisionId,
                                                                   @Valid @RequestBody ReasonRequest request) {
        return Result.success(tasks.createAppeal(decisionId, request.reason()));
    }

    /**
     * 由事项申请方请求当前任务认领人回避；实际组织关系由服务端查验。
     * @param taskId 当前审核或复核任务
     * @param request 回避请求理由
     * @return 回到原岗位队列的任务编号
     */
    @PostMapping("/review-tasks/{taskId}/recusal-requests")
    public Result<String> requestRecusal(@PathVariable long taskId,
                                         @Valid @RequestBody ReasonRequest request) {
        return Result.success(tasks.requestPartyRecusal(taskId, request.reason()));
    }
}
