package com.company.labor.common.exception;

import lombok.Getter;

/**
 * 业务异常
 *
 * <p>业务逻辑中遇到不符合预期的情况时抛出此异常，由全局异常处理器统一处理。
 *
 * <p>使用示例：
 * <pre>
 * // 方式1：使用错误码（默认消息）
 * throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND);
 *
 * // 方式2：使用错误码 + 自定义消息
 * throw new BusinessException(ErrorCode.BAD_REQUEST, "员工姓名不能为空");
 *
 * // 方式3：使用错误码 + 自定义消息 + 原始异常
 * try {
 *     // 业务代码
 * } catch (Exception e) {
 *     throw new BusinessException(ErrorCode.SALARY_CALCULATE_ERROR, "工资计算失败", e);
 * }
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 构造函数（使用错误码默认消息）
     *
     * @param code 错误码
     */
    public BusinessException(Integer code) {
        super(com.company.labor.common.constant.ErrorCode.getMessage(code));
        this.code = code;
        this.message = com.company.labor.common.constant.ErrorCode.getMessage(code);
    }

    /**
     * 构造函数（自定义消息）
     *
     * @param code 错误码
     * @param message 错误消息
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /**
     * 构造函数（自定义消息 + 原始异常）
     *
     * @param code 错误码
     * @param message 错误消息
     * @param cause 原始异常
     */
    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.message = message;
    }
}
