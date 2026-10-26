package com.company.labor.modules.auth;

import com.company.labor.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 维护受控平台岗位授权变更；实际岗位门禁由服务端实时授权服务执行。 */
@RestController
@RequestMapping("/platform/authorizations")
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class PlatformAuthorizationController {
    /** 平台岗位变更和审批用例。 */
    private final PlatformAuthorizationService authorizations;

    /**
     * 创建平台岗位授权 API。
     * @param authorizations 实时授权和审计服务
     */
    public PlatformAuthorizationController(PlatformAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    /**
     * 请求授予、续期、替换或撤销一个平台岗位。
     * @param request 经校验的岗位变更申请
     * @return 已生效或待双人批准的申请状态
     */
    @PostMapping("/changes")
    public Result<PlatformAuthorizationService.ChangeResult> requestChange(
            @Valid @RequestBody AuthorizationChangeBody request) {
        return Result.success(authorizations.requestChange(request.targetAccountId(), request.action(),
                request.roleCode(), request.expiresAt(), request.reason()));
    }

    /**
     * 查询平台授权管理员可处理的待审批监察岗位变更。
     * @return 待处理申请摘要
     */
    @GetMapping("/changes/pending")
    public Result<List<PlatformAuthorizationService.PendingChange>> pendingChanges() {
        return Result.success(authorizations.pendingChanges());
    }

    /**
     * 由不同授权管理员批准一项待处理监察岗位变更。
     * @param requestId 待处理授权申请编号
     * @return 已批准申请状态
     */
    @PostMapping("/changes/{requestId}/approve")
    public Result<PlatformAuthorizationService.ChangeResult> approveChange(@PathVariable long requestId) {
        return Result.success(authorizations.approveChange(requestId));
    }

    /**
     * 由不同授权管理员拒绝一项待处理监察岗位变更并记录理由。
     * @param requestId 待处理授权申请编号
     * @param decision 拒绝理由
     * @return 已拒绝申请编号
     */
    @PostMapping("/changes/{requestId}/reject")
    public Result<Long> rejectChange(@PathVariable long requestId,
                                     @Valid @RequestBody AuthorizationDecisionBody decision) {
        return Result.success(authorizations.rejectChange(requestId, decision.reason()));
    }

    /** 平台岗位变更 HTTP 请求体；有效期由客户端明确给出且由服务端验证。 */
    public record AuthorizationChangeBody(@NotNull @Positive Long targetAccountId,
                                          @NotBlank @Size(max = 16) String action,
                                          @NotBlank @Size(max = 48) String roleCode,
                                          Instant expiresAt,
                                          @NotBlank @Size(max = 500) String reason) {
        /**
         * @param targetAccountId 目标账号编号
         * @param action GRANT、RENEW、REVOKE 或 REPLACE
         * @param roleCode 请求涉及的平台岗位
         * @param expiresAt 非撤销岗位的明确到期时间
         * @param reason 变更理由
         */
    }

    /** 平台岗位双人审批拒绝请求体。 */
    public record AuthorizationDecisionBody(@NotBlank @Size(max = 500) String reason) {
        /**
         * @param reason 具备审计依据的拒绝理由
         */
    }
}
