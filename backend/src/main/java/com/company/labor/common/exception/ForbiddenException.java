package com.company.labor.common.exception;

import com.company.labor.common.constant.ErrorCode;

/**
 * 权限不足异常（403）
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public class ForbiddenException extends BusinessException {

    /** 权限异常的序列化版本。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造函数
     *
     * @param message 错误消息
     */
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    /**
     * 构造函数（带原因）
     *
     * @param message 错误消息
     * @param cause 原因
     */
    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.FORBIDDEN, message, cause);
    }
}
