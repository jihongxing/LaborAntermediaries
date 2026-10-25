package com.company.labor.modules.identity;

import com.company.labor.modules.identity.OrganizationDtos.CreateRequest;
import com.company.labor.modules.identity.OrganizationDtos.JoinRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipDecisionRequest;
import com.company.labor.modules.identity.OrganizationDtos.MembershipView;
import com.company.labor.modules.identity.OrganizationDtos.MembershipRequestView;
import com.company.labor.modules.identity.OrganizationDtos.ReviewRequest;
import com.company.labor.modules.identity.OrganizationDtos.UpdateRequest;
import com.company.labor.modules.identity.OrganizationDtos.View;
import com.company.labor.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供组织申请、加入和平台审核的认证 JSON API；账号注册与令牌签发由外部身份服务负责。 */
@RestController
@RequestMapping("/organizations")
public class OrganizationController {
    /** 组织生命周期和授权业务服务。 */
    private final OrganizationService organizations;

    /**
     * 创建组织接口控制器。
     * @param organizations 组织业务服务
     */
    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    /**
     * 创建组织申请并登记申请人为初始管理员。
     * @param request 主体及授权引用
     * @return 新组织视图
     */
    @PostMapping
    public Result<View> create(@Valid @RequestBody CreateRequest request) {
        return Result.success(organizations.create(request));
    }

    /**
     * 查询当前成员可访问的组织状态；材料引用只返回给组织管理员或平台审核员。
     * @param organizationId 组织标识
     * @return 组织视图
     */
    @GetMapping("/{organizationId}")
    public Result<View> get(@PathVariable long organizationId) {
        return Result.success(organizations.get(organizationId));
    }

    /**
     * 查询平台待审核组织申请。
     * @return 当前审核队列
     */
    @GetMapping("/platform/pending")
    public Result<List<View>> pendingReviews() {
        return Result.success(organizations.pendingReviews());
    }

    /**
     * 提交组织申请供平台审核。
     * @param organizationId 组织标识
     * @return 更新后的组织视图
     */
    @PostMapping("/{organizationId}/submit")
    public Result<View> submit(@PathVariable long organizationId) {
        return Result.success(organizations.submit(organizationId));
    }

    /**
     * 补正仍未通过审核的组织申请资料。
     * @param organizationId 组织标识
     * @param request 修正后的资料和乐观版本
     * @return 更新后的组织视图
     */
    @PutMapping("/{organizationId}")
    public Result<View> update(@PathVariable long organizationId, @Valid @RequestBody UpdateRequest request) {
        return Result.success(organizations.update(organizationId, request));
    }

    /**
     * 申请加入已通过准入的组织。
     * @param organizationId 目标组织
     * @param request 请求的组织内成员角色
     * @return 新加入申请的数据库标识
     */
    @PostMapping("/{organizationId}/join")
    public Result<Long> join(@PathVariable long organizationId, @Valid @RequestBody JoinRequest request) {
        return Result.success(organizations.join(organizationId, request));
    }

    /**
     * 查询本组织待处理的加入申请。
     * @param organizationId 目标组织
     * @return 待审批的成员请求
     */
    @GetMapping("/{organizationId}/membership-requests")
    public Result<List<MembershipRequestView>> pendingMembershipRequests(@PathVariable long organizationId) {
        return Result.success(organizations.pendingMembershipRequests(organizationId));
    }

    /**
     * 由组织管理员决定成员加入请求。
     * @param organizationId 目标组织
     * @param requestId 加入请求标识
     * @param request 管理员决定及拒绝理由
     * @return 获批成员的当前角色
     */
    @PostMapping("/{organizationId}/membership-requests/{requestId}/decision")
    public Result<MembershipView> decideJoin(@PathVariable long organizationId, @PathVariable long requestId,
                                              @Valid @RequestBody MembershipDecisionRequest request) {
        return Result.success(organizations.decideJoin(organizationId, requestId, request));
    }

    /**
     * 由具备平台组织审核权限的身份审批组织申请。
     * @param organizationId 组织标识
     * @param request 审核决定及理由
     * @return 审核后的组织视图
     */
    @PostMapping("/platform/{organizationId}/review")
    public Result<View> review(@PathVariable long organizationId, @Valid @RequestBody ReviewRequest request) {
        return Result.success(organizations.review(organizationId, request));
    }
}
