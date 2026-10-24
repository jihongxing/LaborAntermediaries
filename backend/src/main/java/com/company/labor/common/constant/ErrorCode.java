package com.company.labor.common.constant;

/**
 * 历史错误码常量，保留以兼容已有调用；新底座使用 common.error.PlatformError。
 *
 * <p>错误码规则：
 * <ul>
 *   <li>200 - 成功</li>
 *   <li>400-499 - 客户端错误（参数错误、业务错误等）</li>
 *   <li>500-599 - 服务器错误（系统异常等）</li>
 * </ul>
 *
 * <p>历史业务错误码分组（不是 HTTP 状态码，保留供兼容使用，不代表当前模块边界）：
 * <ul>
 *   <li>1000-1099 - 员工模块</li>
 *   <li>2000-2099 - 工资模块</li>
 *   <li>3000-3099 - 返费模块</li>
 *   <li>4000-4099 - 渠道模块</li>
 *   <li>5000-5099 - 项目模块</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class ErrorCode {

    /** 禁止实例化错误码常量类。 */
    private ErrorCode() {
        // 私有构造函数，防止实例化
    }

    // ==================== 通用错误码 ====================

    /**
     * 成功
     */
    public static final int SUCCESS = 200;

    /**
     * 参数错误
     */
    public static final int BAD_REQUEST = 400;

    /**
     * 未登录
     */
    public static final int UNAUTHORIZED = 401;

    /**
     * 无权限
     */
    public static final int FORBIDDEN = 403;

    /**
     * 资源不存在
     */
    public static final int NOT_FOUND = 404;

    /**
     * 服务器错误
     */
    public static final int INTERNAL_SERVER_ERROR = 500;

    // ==================== 员工模块错误码（1000-1099） ====================

    /**
     * 员工不存在
     */
    public static final int EMPLOYEE_NOT_FOUND = 1001;

    /**
     * 员工已存在（身份证重复）
     */
    public static final int EMPLOYEE_ALREADY_EXISTS = 1002;

    /**
     * 员工状态不正确（如：已离职员工无法操作）
     */
    public static final int EMPLOYEE_STATUS_ERROR = 1003;

    /**
     * 撞单（员工已在其他项目报名）
     */
    public static final int EMPLOYEE_COLLISION = 1004;

    // ==================== 工资模块错误码（2000-2099） ====================

    /**
     * 工资记录不存在
     */
    public static final int SALARY_NOT_FOUND = 2001;

    /**
     * 工资已发放（无法修改）
     */
    public static final int SALARY_ALREADY_PAID = 2002;

    /**
     * 工资计算失败
     */
    public static final int SALARY_CALCULATE_ERROR = 2003;

    /**
     * 工资金额不能为负数
     */
    public static final int SALARY_AMOUNT_NEGATIVE = 2004;

    // ==================== 返费模块错误码（3000-3099） ====================

    /**
     * 返费记录不存在
     */
    public static final int REBATE_NOT_FOUND = 3001;

    /**
     * 返费已结算（无法修改）
     */
    public static final int REBATE_ALREADY_SETTLED = 3002;

    /**
     * 返费计算失败
     */
    public static final int REBATE_CALCULATE_ERROR = 3003;

    /**
     * 未达标（不符合返费条件）
     */
    public static final int REBATE_NOT_QUALIFIED = 3004;

    // ==================== 渠道模块错误码（4000-4099） ====================

    /**
     * 渠道不存在
     */
    public static final int CHANNEL_NOT_FOUND = 4001;

    /**
     * 渠道已禁用
     */
    public static final int CHANNEL_DISABLED = 4002;

    // ==================== 项目模块错误码（5000-5099） ====================

    /**
     * 项目不存在
     */
    public static final int PROJECT_NOT_FOUND = 5001;

    /**
     * 项目已关闭
     */
    public static final int PROJECT_CLOSED = 5002;

    // ==================== 错误码消息映射 ====================

    /**
     * 获取错误码对应的消息
     *
     * @param code 错误码
     * @return 错误消息
     */
    public static String getMessage(int code) {
        return switch (code) {
            case SUCCESS -> "成功";
            case BAD_REQUEST -> "参数错误";
            case UNAUTHORIZED -> "未登录";
            case FORBIDDEN -> "无权限";
            case NOT_FOUND -> "资源不存在";
            case INTERNAL_SERVER_ERROR -> "服务器错误";

            // 员工模块
            case EMPLOYEE_NOT_FOUND -> "员工不存在";
            case EMPLOYEE_ALREADY_EXISTS -> "员工已存在（身份证重复）";
            case EMPLOYEE_STATUS_ERROR -> "员工状态不正确";
            case EMPLOYEE_COLLISION -> "撞单（员工已在其他项目报名）";

            // 工资模块
            case SALARY_NOT_FOUND -> "工资记录不存在";
            case SALARY_ALREADY_PAID -> "工资已发放（无法修改）";
            case SALARY_CALCULATE_ERROR -> "工资计算失败";
            case SALARY_AMOUNT_NEGATIVE -> "工资金额不能为负数";

            // 返费模块
            case REBATE_NOT_FOUND -> "返费记录不存在";
            case REBATE_ALREADY_SETTLED -> "返费已结算（无法修改）";
            case REBATE_CALCULATE_ERROR -> "返费计算失败";
            case REBATE_NOT_QUALIFIED -> "未达标（不符合返费条件）";

            // 渠道模块
            case CHANNEL_NOT_FOUND -> "渠道不存在";
            case CHANNEL_DISABLED -> "渠道已禁用";

            // 项目模块
            case PROJECT_NOT_FOUND -> "项目不存在";
            case PROJECT_CLOSED -> "项目已关闭";

            default -> "未知错误";
        };
    }
}
