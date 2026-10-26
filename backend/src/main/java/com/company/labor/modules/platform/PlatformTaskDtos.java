package com.company.labor.modules.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 平台审核任务 API 的校验请求类型；编号仍使用路径字符串以避免 JSON bigint 精度损失。 */
public final class PlatformTaskDtos {
    /** 禁止实例化仅承载请求类型的命名空间。 */
    private PlatformTaskDtos() {
    }

    /** 释放、回避、异常恢复或申请独立复核时提交的有限说明。 */
    public record ReasonRequest(
            /** 业务动作说明；不会写入通用审计原因码。 */
            @NotBlank @Size(max = 2000) String reason) {
    }

    /** 独立复核人员完成复核时提交的意见和理由。 */
    public record AppealReviewRequest(
            /** 维持原决定或请求重新审核；此意见本身不改写业务状态。 */
            @NotNull PlatformTaskService.AppealOutcome outcome,
            /** 必须留存的独立复核说明。 */
            @NotBlank @Size(max = 2000) String reason) {
    }
}
