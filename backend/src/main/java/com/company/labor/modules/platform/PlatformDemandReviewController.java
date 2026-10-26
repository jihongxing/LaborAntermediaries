package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.demand.DemandDtos.ReviewRequest;
import com.company.labor.modules.demand.DemandDtos.ReviewSubject;
import com.company.labor.modules.demand.DemandDtos.View;
import com.company.labor.modules.demand.DemandService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供需求审核岗位认领后的版本详情和审核决定 API。 */
@RestController
@RequestMapping("/platform/tasks/{taskId}/demand")
public class PlatformDemandReviewController {
    /** 需求服务负责当前版本、岗位、认领、回避及审核状态的领域校验。 */
    private final DemandService demands;

    /**
     * 创建需求审核工作台控制器。
     * @param demands 负责审核任务和需求状态转换的领域服务
     */
    public PlatformDemandReviewController(DemandService demands) {
        this.demands = demands;
    }

    /**
     * 读取本人有效认领绑定的当前需求版本。
     * @param taskId 已认领的需求审核任务编号
     * @return 不含应标、供应商资料或报价记录的需求审核字段
     */
    @GetMapping
    public Result<ReviewSubject> subject(@PathVariable long taskId) {
        return Result.success(demands.reviewSubject(taskId));
    }

    /**
     * 对当前任务绑定版本提交通过、补正或拒绝决定。
     * @param taskId 已认领的需求审核任务编号
     * @param request 决定、公开理由和逐项补正清单
     * @return 审核后的需求状态
     */
    @PostMapping("/decision")
    public Result<View> decide(@PathVariable long taskId, @Valid @RequestBody ReviewRequest request) {
        return Result.success(demands.reviewTask(taskId, request));
    }
}
