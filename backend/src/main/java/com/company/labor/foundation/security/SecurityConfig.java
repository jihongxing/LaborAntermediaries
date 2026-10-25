package com.company.labor.foundation.security;

import com.company.labor.common.error.PlatformError;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.modules.auth.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.nimbusds.jwt.SignedJWT;

/**
 * 配置无状态 JWT 资源服务器、接口访问规则与显式跨域白名单。
 * 身份与组织仅来自已验证令牌，不接受客户端自报身份头。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 使用 RSA 公钥建立 JWT 解码器，并组合签发者、时效、业务声明和撤销校验。
     * @param properties 基础安全配置
     * @param resources 公钥资源加载器
     * @param revocations 令牌撤销查询服务
     * @return 缺少公钥时拒绝全部令牌的解码器，或已配置验证器的解码器
     * @throws IOException 公钥资源读取失败
     */
    @Bean
    public JwtDecoder jwtDecoder(FoundationProperties properties, ResourceLoader resources,
                                 TokenRevocations revocations,
                                 ObjectProvider<AuthSessionService> sessions) throws IOException {
        FoundationProperties.Security settings = properties.getSecurity();
        JwtDecoder external = settings.getPublicKey().isBlank()
                ? token -> { throw new BadJwtException("JWT verification is not configured"); }
                : decoder(loadPublicKey(settings.getPublicKey(), resources), settings.getIssuer(), settings.getAudience(),
                revocations, null);
        FoundationProperties.Authentication localSettings = properties.getAuthentication();
        if (!localSettings.isEnabled()) {
            return external;
        }
        JwtDecoder local = decoder(loadPublicKey(localSettings.getPublicKey(), resources), localSettings.getIssuer(),
                localSettings.getAudience(), revocations, sessions.getIfAvailable());
        return token -> {
            try {
                String issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
                return localSettings.getIssuer().equals(issuer) ? local.decode(token) : external.decode(token);
            } catch (java.text.ParseException exception) {
                throw new BadJwtException("JWT cannot be parsed", exception);
            }
        };
    }

    /**
     * 读取严格配置的 X.509 RSA 公钥资源。
     * @param location Spring 资源位置
     * @param resources Spring 资源加载器
     * @return 已解析 RSA 公钥
     * @throws IOException 资源无法读取或内容不是 X.509 RSA 公钥
     */
    private RSAPublicKey loadPublicKey(String location, ResourceLoader resources) throws IOException {
        try (InputStream input = resources.getResource(location).getInputStream()) {
            return RsaKeyConverters.x509().convert(input);
        }
    }

    /**
     * 为单个发行方配置 RS256 验签和业务声明检查。
     * @param publicKey 发行方 RSA 公钥
     * @param issuer 必须精确匹配的发行方
     * @param audience 必须包含的受众
     * @param revocations JWT 撤销状态查询
     * @param sessions 本地 Refresh Session 查询；外部兼容身份不使用会话声明
     * @return 发行方专用 JWT 解码器
     */
    private JwtDecoder decoder(RSAPublicKey publicKey, String issuer, String audience,
                               TokenRevocations revocations, AuthSessionService sessions) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> required = jwt -> validateClaims(jwt, audience, revocations, sessions);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), required));
        return decoder;
    }

    /**
     * 校验业务要求的声明格式，并检查撤销状态。
     * @param jwt 已解码的令牌
     * @param audience 预期受众
     * @param revocations 撤销记录查询服务
     * @param sessions 本地会话有效性查询
     * @return 校验成功或统一的令牌拒绝结果
     * @throws JwtException 撤销数据库不可用，不能把查询失败视为未撤销
     */
    private OAuth2TokenValidatorResult validateClaims(Jwt jwt, String audience, TokenRevocations revocations,
                                                       AuthSessionService sessions) {
        try {
            if (jwt.getExpiresAt() == null || jwt.getId() == null || jwt.getId().isBlank()
                    || jwt.getId().length() > 200 || jwt.getAudience() == null
                    || !jwt.getAudience().contains(audience)
                    || Long.parseLong(jwt.getSubject()) <= 0) {
                return invalidToken();
            }
            Object tenant = jwt.getClaims().get("tenant_id");
            if (tenant != null && Long.parseLong(tenant.toString()) <= 0) {
                return invalidToken();
            }
            Object permissions = jwt.getClaims().get("permissions");
            if (permissions != null) {
                if (!(permissions instanceof List<?> values) || values.stream()
                        .anyMatch(value -> !(value instanceof String text) || !text.matches("[a-zA-Z0-9:._-]{1,100}"))) {
                    return invalidToken();
                }
            }
            Object sessionId = jwt.getClaims().get("session_id");
            if (sessionId != null) {
                Object contextType = jwt.getClaims().get("context_type");
                Object contextVersion = jwt.getClaims().get("context_version");
                Long tenantId = tenant == null ? null : Long.valueOf(tenant.toString());
                if (!(sessionId instanceof String value) || sessions == null
                        || !(contextType instanceof String type) || contextVersion == null
                        || !sessions.isValidAccessContext(value, Long.parseLong(jwt.getSubject()), type,
                        tenantId, Integer.parseInt(contextVersion.toString()))) {
                    return invalidToken();
                }
            }
            return revocations.isRevoked(jwt.getId()) ? invalidToken() : OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return invalidToken();
        } catch (DataAccessException exception) {
            // 撤销状态无法确认时失败关闭，并避免向客户端泄露数据库异常详情。
            throw new JwtException("Token verification is unavailable");
        }
    }

    /**
     * 生成不泄露具体拒绝原因的校验失败结果。
     * @return 标准 invalid_token 错误
     */
    private OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token rejected", null));
    }

    /**
     * 配置无会话认证链、健康检查例外、监控权限和统一 401/403 响应。
     * @param http Spring Security 构建器
     * @param errors 过滤器层错误输出器
     * @return 应用安全过滤器链
     * @throws Exception 安全过滤器链构建失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HttpErrorWriter errors) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        // 此链依赖 Authorization Bearer 而非 Cookie 会话；权限字符串不额外添加角色前缀。
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access -> access
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/register/**", "/auth/login/**", "/auth/password-reset/**",
                                "/auth/dev/mock-sms/**",
                                "/auth/csrf", "/auth/token/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("foundation:observe")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errors.write(response, PlatformError.UNAUTHENTICATED);
                        })
                        .accessDeniedHandler((request, response, exception) -> errors.write(response, PlatformError.ACCESS_DENIED)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errors.write(response, PlatformError.UNAUTHENTICATED);
                        }))
                .addFilterAfter(new IdentityFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * 仅允许配置的明确来源跨域访问，不允许携带浏览器凭据。
     * @param properties 包含来源白名单的基础配置
     * @return 作用于所有路径的跨域配置源
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(FoundationProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.copyOf(properties.getSecurity().getAllowedOrigins()));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key", "X-CSRF-Token"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        cors.setAllowCredentials(!properties.getSecurity().getAllowedOrigins().isEmpty());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /** 在 Bearer 认证之后将已验证身份绑定到线程，并在请求结束时恢复上下文。 */
    private static class IdentityFilter extends OncePerRequestFilter {
        /**
         * 将 JWT 主体、组织与权限映射为执行身份；无 JWT 身份时直接交给后续过滤器。
         * @param request 当前请求
         * @param response 当前响应
         * @param chain 后续过滤器链
         * @throws ServletException 后续请求处理失败
         * @throws IOException 请求或响应读写失败
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
                Jwt jwt = authentication.getToken();
                Object tenant = jwt.getClaims().get("tenant_id");
                Set<String> permissions = authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority()).collect(Collectors.toUnmodifiableSet());
                ExecutionContext.Identity identity = new ExecutionContext.Identity(Long.parseLong(jwt.getSubject()),
                        tenant == null ? null : Long.valueOf(tenant.toString()), permissions);
                // 即使下游抛出异常也必须关闭作用域，防止容器复用线程时串用身份。
                try (ExecutionContext.Scope ignored = ExecutionContext.open(identity, MDC.get("requestId"))) {
                    chain.doFilter(request, response);
                }
            } else {
                chain.doFilter(request, response);
            }
        }
    }
}
