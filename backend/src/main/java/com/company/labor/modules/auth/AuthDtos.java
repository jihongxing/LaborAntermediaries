package com.company.labor.modules.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 定义手机号认证 API 的请求和仅向受信 Web 控制器公开的响应契约。 */
public final class AuthDtos {
    /**
     * 禁止实例化仅含静态 DTO 的容器类。
     */
    private AuthDtos() {
    }

    /** 发起指定用途短信挑战的匿名请求。 */
    public record ChallengeRequest(@NotBlank @Size(max = 32) String phone) {
        /**
         * @param phone 中国大陆手机号，可使用本地或 +86 E.164 格式
         */
    }

    /** 手机号注册的验证码、凭据与合规告知版本。 */
    public record RegisterRequest(@NotBlank @Size(max = 32) String phone,
                                  @NotBlank @Size(max = 64) String challengeId,
                                  @NotBlank @Size(min = 6, max = 6) String code,
                                  @NotBlank @Size(min = 8, max = 128) String password,
                                  @Size(max = 100) String displayName,
                                  @NotBlank @Size(max = 80) String termsVersion,
                                  @NotBlank @Size(max = 80) String privacyVersion) {
        /**
         * @param phone 发起挑战时使用的手机号
         * @param challengeId 服务端发起接口返回的挑战编号
         * @param code 六位短信验证码
         * @param password 8 至 128 个字符的备用密码
         * @param displayName 可选显示名
         * @param termsVersion 用户协议版本
         * @param privacyVersion 隐私告知版本
         */
    }

    /** 按挑战编号提交短信登录凭证。 */
    public record SmsLoginRequest(@NotBlank @Size(max = 64) String challengeId,
                                  @NotBlank @Size(min = 6, max = 6) String code) {
        /**
         * @param challengeId 服务端发起接口返回的挑战编号
         * @param code 六位短信验证码
         */
    }

    /** 手机号与备用密码登录请求。 */
    public record PasswordLoginRequest(@NotBlank @Size(max = 32) String phone,
                                       @NotBlank @Size(min = 8, max = 128) String password) {
        /**
         * @param phone 中国大陆手机号
         * @param password 备用密码，不在错误响应中回显
         */
    }

    /** 验证密码找回挑战并建立一次性重置票据。 */
    public record VerifyResetRequest(@NotBlank @Size(max = 64) String challengeId,
                                     @NotBlank @Size(min = 6, max = 6) String code) {
        /**
         * @param challengeId 密码找回短信挑战编号
         * @param code 六位短信验证码
         */
    }

    /** 使用一次性重置票据设置新密码。 */
    public record CompleteResetRequest(@NotBlank @Size(max = 64) String ticketId,
                                       @NotBlank @Size(max = 128) String resetToken,
                                       @NotBlank @Size(min = 8, max = 128) String newPassword) {
        /**
         * @param ticketId 验证成功后返回的重置票据 ID
         * @param resetToken 仅返回一次的重置秘密
         * @param newPassword 符合账号密码政策的新密码
         */
    }

    /** 已完成验证码校验的账号基本状态。 */
    public record AccountSummary(long accountId, String status, String displayName) {
        /**
         * @param accountId 全局账号主体编号
         * @param status 账号当前安全状态
         * @param displayName 可选展示名
         */
    }

    /** 面向 API 的会话响应，不包含 Refresh Token 明文。 */
    public record SessionResponse(String accessToken, java.time.Instant accessExpiresAt,
                                  AccountSummary account) {
        /**
         * @param accessToken 短时 RS256 JWT，前端只保存在内存
         * @param accessExpiresAt 访问令牌失效时间
         * @param account 已登录账号摘要
         */
    }

    /** 短信挑战可公开字段，不包含手机号或验证码。 */
    public record ChallengeResponse(String challengeId, java.time.Instant expiresAt,
                                   java.time.Instant resendAfter, String message) {
        /**
         * @param challengeId 服务端生成的随机挑战编号
         * @param expiresAt 挑战失效时间
         * @param resendAfter 最早可重发时间
         * @param message 不区分账号是否存在的统一提示
         */
    }

    /** 密码重置完成短信校验后的一次性票据响应。 */
    public record ResetTicketResponse(String ticketId, String resetToken, java.time.Instant expiresAt) {
        /**
         * @param ticketId 重置票据 ID
         * @param resetToken 随机一次性重置秘密
         * @param expiresAt 票据失效时间
         */
    }

    /** 防止脚本读取 Refresh Cookie 的 CSRF Token JSON 响应。 */
    public record CsrfResponse(String token) {
        /**
         * @param token 与浏览器 CSRF Cookie 配对的短时随机值
         */
    }

    /** 选择组织上下文的请求；组织标识只作为查询目标，最终授权由服务端查询。 */
    public record ContextRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long organizationId) {
        /**
         * @param organizationId 申请切换到的组织编号
         */
    }

    /** 显式绑定外部身份时提交的短期票据与提供商授权码。 */
    public record IdentityBindingRequest(@NotBlank @Size(max = 64) String ticketId,
                                         @NotBlank @Size(max = 128) String ticket,
                                         @NotBlank @Size(max = 2048) String authorizationCode,
                                         @Size(max = 500) String redirectUri) {
        /**
         * @param ticketId 已登录账号绑定票据编号
         * @param ticket 只返回一次的绑定票据秘密
         * @param authorizationCode 提供商签发的一次性授权码，不含外部身份主体
         * @param redirectUri 与发起授权时相同的已登记回调地址
         */
    }

    /** 外部身份绑定完成后仅返回提供商标识。 */
    public record IdentityBindingResponse(String provider) {
        /**
         * @param provider 完成绑定的身份提供商
         */
    }

    /** 当前账号可见的组织申请和成员状态，不包含受限组织资料。 */
    public record OrganizationEntry(long organizationId, String organizationRole, String organizationStatus,
                                    String membershipStatus, String memberRole, String membershipRequestStatus,
                                    String requestedRole) {
        /**
         * @param organizationId 组织编号
         * @param organizationRole 用工方或劳务供应商
         * @param organizationStatus 平台审核及组织运营状态
         * @param membershipStatus 当前成员关系状态，可为空
         * @param memberRole 当前成员岗位，可为空
         * @param membershipRequestStatus 当前加入申请状态，可为空
         * @param requestedRole 加入申请岗位，可为空
         */
    }
}
