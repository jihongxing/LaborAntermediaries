package com.company.labor.modules.bidding;

import com.company.labor.common.Result;
import com.company.labor.modules.bidding.BiddingDtos.AwardRequest;
import com.company.labor.modules.bidding.BiddingDtos.AwardView;
import com.company.labor.modules.bidding.BiddingDtos.BidView;
import com.company.labor.modules.bidding.BiddingDtos.ConfirmationRequest;
import com.company.labor.modules.bidding.BiddingDtos.SnapshotView;
import com.company.labor.modules.bidding.BiddingDtos.SubmitRequest;
import com.company.labor.modules.bidding.BiddingDtos.SupervisionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供 BP-04 应标、选标、确认和 BP-05 交接契约 API。 */
@RestController
@RequestMapping("/bidding")
public class BiddingController {
    /** BP-04 业务服务。 */
    private final BiddingService bidding;

    /** 创建应标控制器。 */
    public BiddingController(BiddingService bidding) {
        this.bidding = bidding;
    }

    /**
     * 提交供应商应标版本。
     * @param demandId 需求标识
     * @param idempotencyKey 幂等键
     * @param request 应标内容
     * @return 当前应标
     */
    @PostMapping("/demands/{demandId}/bids")
    public Result<BidView> submit(@PathVariable long demandId,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @Valid @RequestBody SubmitRequest request) {
        return Result.success(bidding.submit(demandId, idempotencyKey, request));
    }

    /**
     * 撤回当前供应商应标。
     * @param bidId 应标标识
     * @return 撤回后的应标
     */
    @PostMapping("/bids/{bidId}/withdraw")
    public Result<BidView> withdraw(@PathVariable long bidId) {
        return Result.success(bidding.withdraw(bidId));
    }

    /** 读取当前供应商自己的应标。 */
    @GetMapping("/bids/mine")
    public Result<List<BidView>> ownBids() {
        return Result.success(bidding.listOwn());
    }

    /**
     * 读取需求收到的应标，报价只对用工方选标成员可见。
     * @param demandId 需求标识
     * @return 应标列表
     */
    @GetMapping("/demands/{demandId}/bids")
    public Result<List<BidView>> demandBids(@PathVariable long demandId) {
        return Result.success(bidding.listForDemand(demandId));
    }

    /**
     * 截止后授予多个供应商配额。
     * @param demandId 需求标识
     * @param request 选标配额和确认期限
     * @return 授予列表
     */
    @PostMapping("/demands/{demandId}/awards")
    public Result<List<AwardView>> award(@PathVariable long demandId, @Valid @RequestBody AwardRequest request) {
        return Result.success(bidding.award(demandId, request));
    }

    /**
     * 读取需求授予，交易双方只能看到与自身组织相关的记录。
     * @param demandId 需求标识
     * @return 授予列表
     */
    @GetMapping("/demands/{demandId}/awards")
    public Result<List<AwardView>> awards(@PathVariable long demandId) {
        return Result.success(bidding.listAwards(demandId));
    }

    /**
     * 供应商明确确认或拒绝授予。
     * @param awardId 授予标识
     * @param request 决定
     * @return 授予状态
     */
    @PostMapping("/awards/{awardId}/confirmation")
    public Result<AwardView> confirm(@PathVariable long awardId,
                                    @Valid @RequestBody ConfirmationRequest request) {
        return Result.success(bidding.confirm(awardId, request));
    }

    /**
     * 显式释放已超时未确认授予的配额。
     * @param demandId 需求标识
     * @return 释放数量
     */
    @PostMapping("/demands/{demandId}/awards/expire")
    public Result<Integer> expire(@PathVariable long demandId) {
        return Result.success(bidding.expire(demandId));
    }

    /**
     * 读取双方确认后的 BP-05 交接快照。
     * @param demandId 需求标识
     * @return 不可变快照列表
     */
    @GetMapping("/demands/{demandId}/snapshots")
    public Result<List<SnapshotView>> snapshots(@PathVariable long demandId) {
        return Result.success(bidding.snapshots(demandId));
    }

    /**
     * 登记平台监督证据，不提交选标或确认决定。
     * @param demandId 需求标识
     * @param request 监督动作和证据引用
     * @return 监督记录标识
     */
    @PostMapping("/platform/demands/{demandId}/supervision")
    public Result<Long> supervise(@PathVariable long demandId, @Valid @RequestBody SupervisionRequest request) {
        return Result.success(bidding.supervise(demandId, request.action(), request.evidenceReference()));
    }
}
