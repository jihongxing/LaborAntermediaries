package com.company.labor.modules.auth;

import com.company.labor.common.Result;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;

/** 对外提供手机号认证、密码找回和浏览器会话接口，不在响应体返回 Refresh Token。 */
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthController {
    /** 手机号认证用例。 */
    private final AuthenticationService authentication;
    /** Refresh Session 轮换和登出用例。 */
    private final AuthSessionService sessions;
    /** 当前账号可见的组织审核状态查询服务。 */
    private final AuthOrganizationService organizations;
    /** 显式外部身份绑定及一次性票据服务。 */
    private final IdentityBindingService identityBindings;
    /** Cookie、安全来源和签发设置。 */
    private final FoundationProperties.Authentication settings;
    /** 明确允许浏览器携带凭据的来源白名单。 */
    private final java.util.Set<String> allowedOrigins;

    /**
     * 创建认证 HTTP 适配器。
     * @param authentication 手机号认证用例
     * @param sessions Refresh Session 管理服务
     * @param organizations 当前账号的组织状态查询服务
     * @param properties 全局来源及 Cookie 配置
     */
    public AuthController(AuthenticationService authentication, AuthSessionService sessions,
                          AuthOrganizationService organizations, IdentityBindingService identityBindings,
                          FoundationProperties properties) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.organizations = organizations;
        this.identityBindings = identityBindings;
        this.settings = properties.getAuthentication();
        this.allowedOrigins = properties.getSecurity().getAllowedOrigins();
    }

    /**
     * 发起手机号注册验证码；手机号是否已注册不会改变响应文案。
     * @param request 注册手机号
     * @param servletRequest 当前连接信息
     * @return 挑战编号和服务端时间限制
     */
    @PostMapping("/register/start")
    public Result<AuthDtos.ChallengeResponse> startRegistration(@Valid @RequestBody AuthDtos.ChallengeRequest request,
                                                                 HttpServletRequest servletRequest) {
        return Result.success(challenge(authentication.startRegistration(request.phone(), servletRequest.getRemoteAddr())));
    }

    /**
     * 消费注册验证码并建立 ACTIVE 账号和首个浏览器会话。
     * @param request 注册数据及协议版本
     * @param servletRequest 当前连接信息
     * @param response 用于写 Refresh Token Cookie
     * @return 短时 Access Token 与账号状态
     */
    @PostMapping("/register/verify")
    public Result<AuthDtos.SessionResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                      HttpServletRequest servletRequest, HttpServletResponse response) {
        return Result.success(session(authentication.register(request, servletRequest.getRemoteAddr()), response));
    }

    /**
     * 发起短信登录挑战，响应不暴露手机号是否关联账号。
     * @param request 登录手机号
     * @param servletRequest 当前连接信息
     * @return 可公开挑战信息
     */
    @PostMapping("/login/sms/start")
    public Result<AuthDtos.ChallengeResponse> startSmsLogin(@Valid @RequestBody AuthDtos.ChallengeRequest request,
                                                             HttpServletRequest servletRequest) {
        return Result.success(challenge(authentication.startSmsLogin(request.phone(), servletRequest.getRemoteAddr())));
    }

    /**
     * 校验短信验证码并建立当前浏览器 Refresh Session。
     * @param request 挑战编号和验证码
     * @param servletRequest 当前连接信息
     * @param response 用于写 Refresh Token Cookie
     * @return Access Token 和已验证账号摘要
     */
    @PostMapping("/login/sms/verify")
    public Result<AuthDtos.SessionResponse> loginBySms(@Valid @RequestBody AuthDtos.SmsLoginRequest request,
                                                        HttpServletRequest servletRequest, HttpServletResponse response) {
        return Result.success(session(authentication.loginBySms(request.challengeId(), request.code(),
                servletRequest.getRemoteAddr()), response));
    }

    /**
     * 通过手机号和备用密码登录，响应不区分账号不存在与密码错误。
     * @param request 手机号和备用密码
     * @param servletRequest 当前连接信息
     * @param response 用于写 Refresh Token Cookie
     * @return Access Token 和已验证账号摘要
     */
    @PostMapping("/login/password")
    public Result<AuthDtos.SessionResponse> loginByPassword(@Valid @RequestBody AuthDtos.PasswordLoginRequest request,
                                                             HttpServletRequest servletRequest, HttpServletResponse response) {
        return Result.success(session(authentication.loginByPassword(request.phone(), request.password(),
                servletRequest.getRemoteAddr()), response));
    }

    /**
     * 发起密码找回短信，统一响应避免账号枚举。
     * @param request 找回手机号
     * @param servletRequest 当前连接信息
     * @return 可公开挑战信息
     */
    @PostMapping("/password-reset/start")
    public Result<AuthDtos.ChallengeResponse> startPasswordReset(@Valid @RequestBody AuthDtos.ChallengeRequest request,
                                                                  HttpServletRequest servletRequest) {
        return Result.success(challenge(authentication.startPasswordReset(request.phone(), servletRequest.getRemoteAddr())));
    }

    /**
     * 消费找回验证码并签发一次性重置票据。
     * @param request 找回短信挑战及验证码
     * @param servletRequest 当前连接信息
     * @return 一次性票据秘密
     */
    @PostMapping("/password-reset/verify")
    public Result<AuthDtos.ResetTicketResponse> verifyPasswordReset(@Valid @RequestBody AuthDtos.VerifyResetRequest request,
                                                                    HttpServletRequest servletRequest) {
        return Result.success(authentication.verifyPasswordReset(request.challengeId(), request.code(),
                servletRequest.getRemoteAddr()));
    }

    /**
     * 设置新密码并撤销该账号全部 Refresh Session。
     * @param request 一次性找回票据及新密码
     * @param servletRequest 当前连接信息
     * @return 空成功响应
     */
    @PostMapping("/password-reset/complete")
    public Result<Void> completePasswordReset(@Valid @RequestBody AuthDtos.CompleteResetRequest request,
                                               HttpServletRequest servletRequest) {
        authentication.completePasswordReset(request, servletRequest.getRemoteAddr());
        return Result.success();
    }

    /**
     * 取得浏览器双提交 CSRF Token，并写入脚本可见的同源保护 Cookie。
     * @param response 当前 HTTP 响应
     * @return 供后续 Cookie 变更请求放入 X-CSRF-Token 头的随机值
     */
    @GetMapping("/csrf")
    public Result<AuthDtos.CsrfResponse> csrf(HttpServletResponse response) {
        String token = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
        ResponseCookie cookie = ResponseCookie.from(settings.getCsrfCookieName(), token)
                .httpOnly(false).secure(settings.isRefreshCookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(Duration.ofMinutes(10)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.success(new AuthDtos.CsrfResponse(token));
    }

    /**
     * 用精确 Origin 和双提交 CSRF 校验保护基于 Cookie 的刷新接口，再轮换旧会话。
     * @param servletRequest 当前浏览器请求
     * @param response 用于替换 Refresh Cookie
     * @return 新 Access Token
     */
    @PostMapping("/token/refresh")
    public Result<AuthDtos.SessionResponse> refresh(HttpServletRequest servletRequest, HttpServletResponse response) {
        requireBrowserMutation(servletRequest);
        AuthSessionService.SessionTokens tokens = sessions.rotate(cookie(servletRequest, settings.getRefreshCookieName()));
        setRefreshCookie(response, tokens.refreshToken());
        return Result.success(new AuthDtos.SessionResponse(tokens.accessToken(), tokens.accessExpiresAt(), null));
    }

    /**
     * 撤销当前浏览器会话并清除认证和 CSRF Cookie。
     * @param servletRequest 当前浏览器请求
     * @param response 用于删除 Cookie
     * @return 空成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest servletRequest, HttpServletResponse response) {
        requireBrowserMutation(servletRequest);
        sessions.logout(cookie(servletRequest, settings.getRefreshCookieName()));
        clearCookie(response, settings.getRefreshCookieName(), true);
        clearCookie(response, settings.getCsrfCookieName(), false);
        return Result.success();
    }

    /**
     * 查询当前本地账号摘要；身份主体只取自已验证的 Access Token。
     * @return 当前认证主体编号
     */
    @GetMapping("/me")
    public Result<AuthDtos.AccountSummary> me() {
        long accountId = ExecutionContext.require().actorId();
        return Result.success(authentication.currentAccount(accountId));
    }

    /**
     * 查询当前账号的组织审核、成员授权和加入申请状态。
     * @return 当前账号参与或申请加入的组织状态
     */
    @GetMapping("/organizations")
    public Result<java.util.List<AuthDtos.OrganizationEntry>> organizations() {
        return Result.success(organizations.list(ExecutionContext.require().actorId()));
    }

    /**
     * 为当前已登录账号创建十分钟有效、只能消费一次的外部身份绑定票据。
     * @param provider 已批准的外部提供商标识
     * @return 一次性绑定秘密；调用方应只在当前显式绑定流程中使用
     */
    @PostMapping("/identities/{provider}/binding-ticket")
    public Result<IdentityBindingService.BindingTicket> startIdentityBinding(
            @org.springframework.web.bind.annotation.PathVariable String provider) {
        return Result.success(identityBindings.issue(provider));
    }

    /**
     * 由提供商 SPI 服务端交换授权码，并显式绑定到当前登录账号；不会自动合并其他账号。
     * @param provider 票据预定的外部提供商
     * @param request 一次性票据和提供商授权码
     * @return 最小化的绑定完成响应
     */
    @PostMapping("/identities/{provider}/bind")
    public Result<AuthDtos.IdentityBindingResponse> completeIdentityBinding(
            @org.springframework.web.bind.annotation.PathVariable String provider,
            @Valid @RequestBody AuthDtos.IdentityBindingRequest request) {
        IdentityBindingService.BindingResult result = identityBindings.complete(
                new IdentityBindingService.CompleteBinding(provider, request.ticketId(), request.ticket(),
                        request.authorizationCode(), request.redirectUri()));
        return Result.success(new AuthDtos.IdentityBindingResponse(result.provider()));
    }

    /**
     * 在已审核组织中切换当前浏览器会话的服务端授权上下文。
     * @param request 客户端选择的组织编号
     * @param token 已验证的本地 JWT 主体和会话声明
     * @param servletRequest 当前浏览器请求，用于双提交 CSRF 检查
     * @return 新组织上下文 Access Token；Refresh Cookie 不变
     * @throws BusinessException 来源、CSRF、组织或成员授权校验失败
     */
    @PostMapping("/context")
    public Result<AuthDtos.SessionResponse> selectContext(@Valid @RequestBody AuthDtos.ContextRequest request,
                                                          JwtAuthenticationToken token,
                                                          HttpServletRequest servletRequest) {
        requireBrowserMutation(servletRequest);
        long accountId = Long.parseLong(token.getToken().getSubject());
        String sessionId = token.getToken().getClaimAsString("session_id");
        AuthSessionService.SessionTokens tokens = sessions.selectOrganization(accountId, sessionId,
                request.organizationId());
        return Result.success(new AuthDtos.SessionResponse(tokens.accessToken(), tokens.accessExpiresAt(), null));
    }

    /**
     * 将当前浏览器会话切换为服务端当前有效的平台岗位上下文。
     * @param token 已验证的本地访问令牌
     * @param servletRequest 当前浏览器请求，用于双提交 CSRF 检查
     * @return 新的平台上下文访问令牌；Refresh Cookie 不变
     * @throws BusinessException 来源、CSRF、会话或平台授权校验失败
     */
    @PostMapping("/platform-context")
    public Result<AuthDtos.SessionResponse> selectPlatformContext(JwtAuthenticationToken token,
                                                                   HttpServletRequest servletRequest) {
        requireBrowserMutation(servletRequest);
        long accountId = Long.parseLong(token.getToken().getSubject());
        String sessionId = token.getToken().getClaimAsString("session_id");
        AuthSessionService.SessionTokens tokens = sessions.selectPlatformContext(accountId, sessionId);
        return Result.success(new AuthDtos.SessionResponse(tokens.accessToken(), tokens.accessExpiresAt(), null));
    }

    /**
     * 将内部挑战值转换为统一匿名响应，不包含手机号是否已注册。
     * @param challenge 持久化挑战时间信息
     * @return 公开 API 挑战结果
     */
    private AuthDtos.ChallengeResponse challenge(AuthChallengeService.ChallengeStart challenge) {
        return new AuthDtos.ChallengeResponse(challenge.challengeId(), challenge.expiresAt(), challenge.resendAfter(),
                "如果该手机号可以接收此类验证码，短信将按安全策略发送。");
    }

    /**
     * 输出 Access Token 并将 Refresh Token 限制在 HttpOnly Cookie。
     * @param result 认证用例结果
     * @param response 当前响应
     * @return 可由前端内存保存的访问令牌与账号摘要
     */
    private AuthDtos.SessionResponse session(AuthenticationService.AuthResult result, HttpServletResponse response) {
        setRefreshCookie(response, result.tokens().refreshToken());
        return new AuthDtos.SessionResponse(result.tokens().accessToken(), result.tokens().accessExpiresAt(), result.account());
    }

    /**
     * 写入只能由浏览器自动携带、脚本不能读取的轮换 Cookie。
     * @param response 当前响应
     * @param refreshToken 新 Refresh Token
     */
    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(settings.getRefreshCookieName(), refreshToken)
                .httpOnly(true).secure(settings.isRefreshCookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(settings.getRefreshSessionTtl()).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 设置立即过期的同名 Cookie。
     * @param response 当前响应
     * @param name Cookie 名称
     * @param httpOnly 是否保留 Refresh Cookie 的 HttpOnly 限制
     */
    private void clearCookie(HttpServletResponse response, String name, boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, "").httpOnly(httpOnly)
                .secure(settings.isRefreshCookieSecure()).sameSite("Strict").path("/api/auth").maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 强制校验允许来源和双提交 token，避免跨站请求借用浏览器 Refresh Cookie。
     * @param request 当前变更请求
     * @throws BusinessException Origin 或 CSRF Token 缺失、不匹配或不在白名单
     */
    private void requireBrowserMutation(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String cookieToken = cookie(request, settings.getCsrfCookieName());
        String headerToken = request.getHeader("X-CSRF-Token");
        if (origin == null || !allowedOrigins.contains(origin) || cookieToken == null || headerToken == null
                || !MessageDigest.isEqual(cookieToken.getBytes(StandardCharsets.UTF_8), headerToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(PlatformError.AUTH_CSRF_REQUIRED);
        }
    }

    /**
     * 从 Servlet Cookie 数组中按完全相同的 Cookie 名读取值。
     * @param request 当前请求
     * @param name 受控配置的 Cookie 名称
     * @return Cookie 值，不存在时为 null
     */
    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(cookie -> cookie.getName().equals(name)).map(Cookie::getValue)
                .findFirst().orElse(null);
    }
}
