package com.company.labor.modules.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 首批组织与成员接口使用的请求、响应数据结构；不承载证件正文或身份凭据。 */
public final class OrganizationDtos {
    /** 工具容器不允许实例化。 */
    private OrganizationDtos() {
    }

    /**
     * 创建组织申请所需的主体和授权引用。
     * @param organizationRole 单一组织业务角色
     * @param legalName 登记名称
     * @param registrationId 组织登记标识
     * @param authorizationReference 组织控制权或经办授权的受控引用
     * @param qualificationReference 适用资质/备案材料的受控引用
     */
    public record CreateRequest(
            @NotNull OrganizationRole organizationRole,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 100) String registrationId,
            @NotBlank @Size(max = 200) String authorizationReference,
            String qualificationReference) {
    }

    /**
     * 补正组织草稿时使用的资料和乐观版本。
     * @param expectedVersion 客户端看到的当前组织版本
     * @param legalName 登记名称
     * @param registrationId 组织登记标识
     * @param authorizationReference 组织控制权或经办授权的受控引用
     * @param qualificationReference 适用资质/备案材料的受控引用
     */
    public record UpdateRequest(
            @NotNull @jakarta.validation.constraints.Positive Long expectedVersion,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 100) String registrationId,
            @NotBlank @Size(max = 200) String authorizationReference,
            @Size(max = 200) String qualificationReference) {
    }

    /**
     * 组织审核决定；补正和拒绝必须提供理由。
     * @param decision 通过、补正或拒绝
     * @param reason 面向申请人的审核理由
     */
    public record ReviewRequest(@NotNull OrganizationReviewDecision decision, String reason) {
    }

    /**
     * 加入组织请求的成员操作角色。
     * @param requestedRole 申请的组织内角色，不能申请管理员角色
     */
    public record JoinRequest(@NotNull MembershipRole requestedRole) {
    }

    /**
     * 管理员处理加入请求的决定。
     * @param decision 批准或拒绝
     * @param reason 拒绝原因
     */
    public record MembershipDecisionRequest(@NotNull MembershipRequestDecision decision, String reason) {
    }

    /**
     * 返回组织及当前申请版本的公开状态。
     * @param id 组织标识
     * @param organizationRole 组织角色
     * @param legalName 登记名称
     * @param registrationId 组织登记标识
     * @param authorizationReference 受控的经办授权材料引用
     * @param qualificationReference 受控的供应商资质材料引用
     * @param status 当前组织状态
     * @param version 乐观版本
     * @param applicationVersion 只追加的申请资料版本
     * @param createdBy 创建操作者
     * @param reviewedBy 最近审核人
     * @param reviewReason 最近审核理由
     */
    public record View(
            long id,
            OrganizationRole organizationRole,
            String legalName,
            String registrationId,
            String authorizationReference,
            String qualificationReference,
            OrganizationStatus status,
            long version,
            long applicationVersion,
            long createdBy,
            Long reviewedBy,
            String reviewReason) {
    }

    /** 返回已登录成员在组织中的当前角色。 */
    public record MembershipView(long organizationId, long actorId, MembershipRole memberRole,
                                 MembershipStatus status) {
    }

    /** 组织管理员可见的待审批加入请求摘要。 */
    public record MembershipRequestView(long requestId, long actorId, MembershipRole requestedRole,
                                        Instant createdAt) {
    }

    /** 组织审核决定枚举。 */
    public enum OrganizationReviewDecision {
        /** 组织准入通过。 */
        APPROVE,
        /** 退回申请人补正。 */
        NEEDS_CORRECTION,
        /** 拒绝本次组织申请。 */
        REJECT
    }

    /** 成员加入请求决定枚举。 */
    public enum MembershipRequestDecision {
        /** 创建成员关系。 */
        APPROVE,
        /** 拒绝创建成员关系。 */
        REJECT
    }
}
