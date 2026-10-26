package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.identity.OrganizationDtos.ReviewRequest;
import com.company.labor.modules.identity.OrganizationDtos.View;
import com.company.labor.modules.identity.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供组织审核岗位认领后的详情、审核决定和申请方可见结果接口。 */
@RestController
@RequestMapping("/platform/tasks/{taskId}/organization")
public class PlatformOrganizationReviewController {
    /** 组织审核状态服务；服务端再次校验任务、版本和回避关系。 */
    private final OrganizationService organizations;

    /**
     * 创建组织审核工作台 API。
     * @param organizations 组织审核领域服务
     */
    public PlatformOrganizationReviewController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    /**
     * 读取当前审核员已认领任务绑定的组织申请版本。
     * @param taskId 已认领的组织审核任务编号
     * @return 仅含审核该版本所需的最小申请字段
     */
    @GetMapping
    public Result<OrganizationService.OrganizationReviewSubject> subject(@PathVariable long taskId) {
        return Result.success(organizations.reviewSubject(taskId));
    }

    /**
     * 为已认领版本提交通过、补正或拒绝决定。
     * @param taskId 已认领的组织审核任务编号
     * @param request 决定、申请方可见理由和逐项补正内容
     * @return 领域事务成功后组织申请的当前状态
     */
    @PostMapping("/decision")
    public Result<View> decide(@PathVariable long taskId, @Valid @RequestBody ReviewRequest request) {
        return Result.success(organizations.reviewTask(taskId, request));
    }
}
