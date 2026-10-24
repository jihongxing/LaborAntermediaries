package com.company.labor.common.error;

/**
 * 可供底座与业务模块共同实现的错误契约，错误标识、业务码与 HTTP 状态互相独立。
 * 实现应为不可变常量，消息必须适合公开，不得携带敏感上下文。
 */
public interface ErrorDefinition {
    /**
     * 获取稳定的数字业务码，新码不得占用 HTTP 状态或历史业务码空间。
     * @return 六位业务码，底座使用 100000—199999
     */
    int code();

    /**
     * 获取供客户端判断的稳定标识，不应根据展示文案判断错误类型。
     * @return 带命名空间的错误标识
     */
    String key();

    /**
     * 获取真实 HTTP 错误状态。
     * @return 400—599 的状态码
     */
    int httpStatus();

    /**
     * 获取不含内部细节的默认提示。
     * @return 可公开的错误消息
     */
    String message();

    /**
     * 校验共享错误契约，防止模块将业务码误当作 HTTP 状态或使用非稳定标识。
     * @throws IllegalArgumentException 编号、标识、状态或消息不符合契约
     */
    default void validate() {
        if (code() < 100000 || code() > 999999 || httpStatus() < 400 || httpStatus() > 599
                || key() == null || !key().matches("[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*")
                || message() == null || message().isBlank()) {
            throw new IllegalArgumentException("Invalid error definition");
        }
    }
}
