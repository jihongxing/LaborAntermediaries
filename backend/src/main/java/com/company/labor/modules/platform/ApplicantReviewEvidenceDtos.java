package com.company.labor.modules.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/** 申请方绑定组织或需求版本材料时使用的受限请求结构。 */
public final class ApplicantReviewEvidenceDtos {
    /** 禁止实例化仅承载 API 请求结构的命名空间。 */
    private ApplicantReviewEvidenceDtos() {
    }

    /** 将本人上传的私有文件关联到明确的可编辑资源版本。
     * @param expectedVersion 客户端当前编辑的业务版本
     * @param fileId 当前组织范围内本人上传的私有文件编号
     * @param category 申请方声明的粗粒度材料类别
     */
    public record AttachRequest(
            /** 客户端读取并正在编辑的精确版本，服务端必须与资源当前版本比对。 */
            @Positive long expectedVersion,
            /** 组织范围内本人通过私有文件接口上传的文件编号。 */
            @NotBlank @Pattern(regexp = "[a-f0-9]{32}") String fileId,
            /** 申请方声明的粗粒度类别，不等于平台真实性结论。 */
            @NotNull ReviewEvidenceCategory category) {
    }
}
