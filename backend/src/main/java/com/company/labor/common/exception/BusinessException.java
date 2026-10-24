package com.company.labor.common.exception;

import com.company.labor.common.error.ErrorDefinition;
import lombok.Getter;

/**
 * 业务异常
 *
 * <p>业务逻辑中遇到不符合预期的情况时抛出此异常，由全局异常处理器统一处理。
 * 消息会回传客户端，不能包含 SQL、凭证或其他内部敏感信息；原始异常仅用于保留原因。
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

    /** 业务异常的序列化版本。 */
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;

    /** 真实 HTTP 状态；历史业务码不是 HTTP 状态时使用 422。 */
    private final int httpStatus;

    /** 稳定错误标识；历史数字码构造方式不提供该字段。 */
    private final String errorKey;

    /**
     * 使用独立业务码与 HTTP 状态的错误契约创建异常。
     * @param error 不可变错误定义，消息必须可公开
     */
    public BusinessException(ErrorDefinition error) {
        this(error, null);
    }

    /**
     * 创建带原始原因的契约化异常，原始原因不进入响应体。
     * @param error 不可变错误定义
     * @param cause 仅供内部诊断的原因，可为 null
     * @throws IllegalArgumentException 错误定义不满足编号、命名或状态约束
     */
    public BusinessException(ErrorDefinition error, Throwable cause) {
        super(error.message(), cause);
        error.validate();
        this.code = error.code();
        this.message = error.message();
        this.httpStatus = error.httpStatus();
        this.errorKey = error.key();
    }

    /**
     * 构造函数（使用错误码默认消息）
     *
     * @param code 错误码
     */
    public BusinessException(Integer code) {
        this(code, com.company.labor.common.constant.ErrorCode.getMessage(code), null);
    }

    /**
     * 构造函数（自定义消息）
     *
     * @param code 错误码
     * @param message 错误消息
     */
    public BusinessException(Integer code, String message) {
        this(code, message, null);
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
        this.httpStatus = code >= 400 && code <= 599 ? code : 422;
        this.errorKey = null;
    }
}
