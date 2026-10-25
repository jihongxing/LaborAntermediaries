package com.company.labor.modules.demand;

import com.company.labor.modules.demand.DemandDtos.ReviewRequest;
import com.company.labor.modules.demand.DemandDtos.UpsertRequest;
import com.company.labor.modules.demand.DemandDtos.View;
import com.company.labor.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供需求草稿、审核、版本、发布、暂停和撤回 API；履约和资金操作留给后续业务包。 */
@RestController
@RequestMapping("/demands")
public class DemandController {
    /** 需求生命周期业务服务。 */
    private final DemandService demands;

    /**
     * 创建需求控制器。
     * @param demands 需求业务服务
     */
    public DemandController(DemandService demands) {
        this.demands = demands;
    }

    /**
     * 创建需求草稿。
     * @param organizationId 用工方组织
     * @param request 需求第一版内容
     * @return 新需求
     */
    @PostMapping("/organizations/{organizationId}")
    public Result<View> create(@PathVariable long organizationId,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody UpsertRequest request) {
        return Result.success(demands.create(organizationId, idempotencyKey, request));
    }

    /**
     * 读取需求。
     * @param demandId 需求标识
     * @return 需求视图
     */
    @GetMapping("/{demandId}")
    public Result<View> get(@PathVariable long demandId) {
        return Result.success(demands.get(demandId));
    }

    /**
     * 查询当前组织的需求版本摘要。
     * @param organizationId 当前组织
     * @return 当前组织最近更新的需求
     */
    @GetMapping("/organizations/{organizationId}")
    public Result<List<View>> listForOrganization(@PathVariable long organizationId) {
        return Result.success(demands.listForOrganization(organizationId));
    }

    /**
     * 读取开放应标需求。
     * @return 当前劳务公司可见的需求
     */
    @GetMapping("/open-for-bid")
    public Result<List<View>> openForBid() {
        return Result.success(demands.openForBid());
    }

    /**
     * 查询平台需求审核队列。
     * @return 待审核需求
     */
    @GetMapping("/platform/pending")
    public Result<List<View>> pendingReviews() {
        return Result.success(demands.pendingReviews());
    }

    /**
     * 追加需求草稿版本。
     * @param demandId 需求标识
     * @param revision 客户端看到的当前版本
     * @param request 新版本内容
     * @return 更新后的需求
     */
    @PutMapping("/{demandId}/revisions/{revision}")
    public Result<View> update(@PathVariable long demandId, @PathVariable long revision,
                               @Valid @RequestBody UpsertRequest request) {
        return Result.success(demands.update(demandId, revision, request));
    }

    /**
     * 提交需求审核。
     * @param demandId 需求标识
     * @return 待审核需求
     */
    @PostMapping("/{demandId}/submit")
    public Result<View> submit(@PathVariable long demandId) {
        return Result.success(demands.submit(demandId));
    }

    /**
     * 执行平台需求审核。
     * @param demandId 需求标识
     * @param request 审核决定
     * @return 审核后的需求
     */
    @PostMapping("/platform/{demandId}/review")
    public Result<View> review(@PathVariable long demandId, @Valid @RequestBody ReviewRequest request) {
        return Result.success(demands.review(demandId, request));
    }

    /**
     * 发布已审核需求。
     * @param demandId 需求标识
     * @return 开放应标需求
     */
    @PostMapping("/{demandId}/publish")
    public Result<View> publish(@PathVariable long demandId) {
        return Result.success(demands.publish(demandId));
    }

    /**
     * 暂停开放需求并关闭新增应标入口。
     * @param demandId 需求标识
     * @return 暂停后的需求
     */
    @PostMapping("/{demandId}/suspend")
    public Result<View> suspend(@PathVariable long demandId) {
        return Result.success(demands.suspend(demandId));
    }

    /**
     * 撤回需求并关闭未完成交易。
     * @param demandId 需求标识
     * @return 关闭后的需求
     */
    @PostMapping("/{demandId}/close")
    public Result<View> close(@PathVariable long demandId) {
        return Result.success(demands.close(demandId));
    }
}
