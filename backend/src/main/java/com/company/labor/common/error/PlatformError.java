package com.company.labor.common.error;

/** 平台技术错误目录；不包含收费资格、资金审批或交易状态等业务规则。 */
public enum PlatformError implements ErrorDefinition {
    /** 请求参数或请求体不合法。 */
    INVALID_ARGUMENT(100001, "COMMON.INVALID_ARGUMENT", 400, "请求参数或请求体不合法"),
    /** 当前请求没有通过认证。 */
    UNAUTHENTICATED(100002, "SECURITY.UNAUTHENTICATED", 401, "请先完成身份认证"),
    /** 身份缺少操作权限或资源授权。 */
    ACCESS_DENIED(100003, "SECURITY.ACCESS_DENIED", 403, "无权执行此操作"),
    /** 当前身份没有明确的组织范围。 */
    ORGANIZATION_REQUIRED(100004, "SECURITY.ORGANIZATION_REQUIRED", 403, "当前身份缺少组织范围"),
    /** 资源不存在，或为避免泄露存在性而隐藏资源。 */
    RESOURCE_NOT_FOUND(100005, "COMMON.RESOURCE_NOT_FOUND", 404, "资源不存在或不可访问"),
    /** 数据约束或并发修改造成冲突。 */
    DATA_CONFLICT(100006, "COMMON.DATA_CONFLICT", 409, "数据冲突，请刷新后重试"),
    /** 基于过期版本提交修改。 */
    VERSION_CONFLICT(100007, "COMMON.VERSION_CONFLICT", 409, "版本已变化，请刷新后重试"),
    /** 同一幂等键对应不同请求内容。 */
    IDEMPOTENCY_CONFLICT(100008, "RELIABILITY.IDEMPOTENCY_CONFLICT", 409, "幂等键已用于其他请求内容"),
    /** 同一事务内的幂等操作尚未完成。 */
    OPERATION_IN_PROGRESS(100009, "RELIABILITY.OPERATION_IN_PROGRESS", 409, "操作正在处理中，请勿重复提交"),
    /** 复核操作者与需回避人员重合。 */
    INDEPENDENT_REVIEW_REQUIRED(100010, "SECURITY.INDEPENDENT_REVIEW_REQUIRED", 403, "此操作需要独立人员复核"),
    /** 请求体或附件超过大小限制。 */
    PAYLOAD_TOO_LARGE(100011, "COMMON.PAYLOAD_TOO_LARGE", 413, "请求内容超过大小限制"),
    /** 路由不支持该请求方法。 */
    METHOD_NOT_ALLOWED(100012, "COMMON.METHOD_NOT_ALLOWED", 405, "不支持此请求方法"),
    /** 请求媒体类型不受支持。 */
    UNSUPPORTED_MEDIA_TYPE(100013, "COMMON.UNSUPPORTED_MEDIA_TYPE", 415, "不支持此请求内容类型"),
    /** 保存内容与完整性摘要不一致，拒绝继续使用。 */
    INTEGRITY_FAILURE(100014, "RELIABILITY.INTEGRITY_FAILURE", 500, "内容完整性检查失败"),
    /** 未分类的服务端失败，不向客户端公开内部异常。 */
    INTERNAL_ERROR(100015, "COMMON.INTERNAL_ERROR", 500, "服务暂时不可用，请稍后重试"),
    /** 认证凭据不正确或认证流程不能继续，使用统一文案避免账号枚举。 */
    AUTHENTICATION_FAILED(100016, "AUTH.AUTHENTICATION_FAILED", 401, "认证信息不正确或已失效"),
    /** 短信验证码无效、过期、重放或超过尝试次数。 */
    AUTH_CHALLENGE_INVALID(100017, "AUTH.CHALLENGE_INVALID", 400, "验证码无效或已失效"),
    /** 短信或登录请求触发了安全限流。 */
    AUTH_RATE_LIMITED(100018, "AUTH.RATE_LIMITED", 429, "请求过于频繁，请稍后重试"),
    /** 账号暂时锁定，外部提示不泄露具体锁定原因。 */
    AUTH_ACCOUNT_LOCKED(100019, "AUTH.ACCOUNT_LOCKED", 423, "账号暂时不可用，请稍后重试"),
    /** Cookie 会话变更请求缺少或未通过 CSRF 校验。 */
    AUTH_CSRF_REQUIRED(100020, "AUTH.CSRF_REQUIRED", 403, "请求安全校验失败"),
    /** 外部登录身份已属于其他账号，必须走受控绑定或找回流程。 */
    AUTH_IDENTITY_CONFLICT(100021, "AUTH.IDENTITY_CONFLICT", 409, "登录身份已绑定其他账号"),
    /** 当前账号尚未满足敏感操作所需的手机号或恢复因子要求。 */
    AUTH_RECOVERY_REQUIRED(100022, "AUTH.RECOVERY_REQUIRED", 403, "请先完成手机号绑定或安全验证"),
    /** 请求的外部身份提供商尚未配置可用适配器。 */
    AUTH_PROVIDER_UNAVAILABLE(100023, "AUTH.PROVIDER_UNAVAILABLE", 503, "该身份提供商暂不可用");

    /** 稳定的六位数字业务码。 */
    private final int code;
    /** 稳定的命名空间错误标识。 */
    private final String key;
    /** 独立于业务码的 HTTP 状态。 */
    private final int httpStatus;
    /** 不包含敏感信息的默认提示。 */
    private final String message;

    /**
     * 声明平台错误常量。
     * @param code 六位数字业务码
     * @param key 稳定错误标识
     * @param httpStatus HTTP 错误状态
     * @param message 安全提示
     */
    PlatformError(int code, String key, int httpStatus, String message) {
        this.code = code;
        this.key = key;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /** @return 稳定的数字业务码 */
    @Override
    public int code() {
        return code;
    }

    /** @return 供客户端判断的稳定错误标识 */
    @Override
    public String key() {
        return key;
    }

    /** @return 对应的 HTTP 错误状态 */
    @Override
    public int httpStatus() {
        return httpStatus;
    }

    /** @return 可向客户端公开的默认提示 */
    @Override
    public String message() {
        return message;
    }
}
