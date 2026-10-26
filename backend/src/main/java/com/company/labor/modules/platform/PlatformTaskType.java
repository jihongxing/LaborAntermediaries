package com.company.labor.modules.platform;

import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;

/** 首期审核任务种类及其唯一平台权限映射。 */
public enum PlatformTaskType {
    /** 组织准入审核任务。 */
    ORGANIZATION_REVIEW("ORGANIZATION", "platform:organizations:review"),
    /** 用工需求审核任务。 */
    DEMAND_REVIEW("DEMAND", "platform:demands:review"),
    /** 对原组织或需求审核决定的独立复核任务。 */
    APPEAL_REVIEW("APPEAL", "platform:appeals:review");

    /** 数据库中稳定的资源类型。 */
    private final String resourceType;
    /** 认领、查看和处理任务所需的平台权限。 */
    private final String permission;

    /**
     * 声明一种任务及其服务端权限映射。
     * @param resourceType 稳定资源类型代码
     * @param permission 必须具备的完整平台权限码
     */
    PlatformTaskType(String resourceType, String permission) {
        this.resourceType = resourceType;
        this.permission = permission;
    }

    /**
     * 返回该任务的权限码。
     * @return 完整平台权限码
     */
    public String permission() {
        return permission;
    }

    /**
     * 返回该任务对应的原始业务资源类型。
     * @return ORGANIZATION 或 DEMAND
     */
    public String resourceType() {
        return resourceType;
    }

    /**
     * 严格解析 API 或数据库任务代码。
     * @param code 待解析代码
     * @return 已知任务种类
     * @throws BusinessException 代码为空或不在首期任务目录
     */
    public static PlatformTaskType fromCode(String code) {
        for (PlatformTaskType type : values()) {
            if (type.name().equals(code)) {
                return type;
            }
        }
        throw new BusinessException(PlatformError.INVALID_ARGUMENT);
    }

    /**
     * 将事项资源类型映射为普通审核任务类型。
     * @param type 组织或需求事项资源类型
     * @return 对应的普通审核任务
     * @throws BusinessException 事项类型不受首期审核队列支持
     */
    public static PlatformTaskType forReviewCase(ReviewCaseType type) {
        return switch (type) {
            case ORGANIZATION -> ORGANIZATION_REVIEW;
            case DEMAND -> DEMAND_REVIEW;
        };
    }
}
