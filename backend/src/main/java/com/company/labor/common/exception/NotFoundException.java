package com.company.labor.common.exception;

import com.company.labor.common.constant.ErrorCode;

/**
 * 资源不存在异常（404）
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public class NotFoundException extends BusinessException {

    /** 资源缺失异常的序列化版本。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造函数
     *
     * @param message 错误消息
     */
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    /**
     * 构造函数（带原因）
     *
     * @param message 错误消息
     * @param cause 原因
     */
    public NotFoundException(String message, Throwable cause) {
        super(ErrorCode.NOT_FOUND, message, cause);
    }
}
