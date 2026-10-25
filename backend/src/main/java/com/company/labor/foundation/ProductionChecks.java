package com.company.labor.foundation;

import com.company.labor.foundation.crypto.CryptoService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 启动时验证基础配置，生产环境缺少安全依赖时直接阻止应用启动。 */
@Component
public class ProductionChecks implements InitializingBean {
    /** 待检查的基础能力配置。 */
    private final FoundationProperties properties;
    /** 用于判断活动加密密钥是否就绪的服务。 */
    private final CryptoService crypto;
    /** 用于识别 prod 配置档的运行环境。 */
    private final Environment environment;

    /**
     * 注入启动检查依赖。
     * @param properties 基础配置
     * @param crypto 加密服务
     * @param environment 运行环境
     */
    public ProductionChecks(FoundationProperties properties, CryptoService crypto, Environment environment) {
        this.properties = properties;
        this.crypto = crypto;
        this.environment = environment;
    }

    /**
     * 校验任务与认证时间、跨域、Mock SMS profile 限制，并检查生产环境的安全配置。
     * @throws IllegalStateException 租约、重试或安全配置不符合要求
     */
    @Override
    public void afterPropertiesSet() {
        if (properties.getTasks().getLease().isNegative() || properties.getTasks().getLease().isZero()
                || properties.getTasks().getRetryDelay().isNegative()) {
            throw new IllegalStateException("Task lease must be positive and retry delay must not be negative");
        }
        if (properties.getSecurity().getAllowedOrigins().contains("*")) {
            throw new IllegalStateException("CORS requires explicit origins");
        }
        validateAuthenticationConfiguration();
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // 开发环境可不配安全材料；生产必须失败关闭，不能以降级方式绕过校验。
            if (properties.getSecurity().getPublicKey().isBlank() || !crypto.isConfigured()
                    || !properties.getStorage().getRoot().isAbsolute()) {
                throw new IllegalStateException("Production requires JWT verification, encryption and an absolute private storage path");
            }
        }
    }

    /**
     * 检查认证期限为正数，并将 Mock SMS 严格限制在 local/test profile。
     * @throws IllegalStateException 认证期限无效或 Mock SMS 在其他 profile 中启用
     */
    private void validateAuthenticationConfiguration() {
        FoundationProperties.Authentication authentication = properties.getAuthentication();
        if (!positive(authentication.getAccessTokenTtl()) || !positive(authentication.getRefreshSessionTtl())
                || !positive(authentication.getMockSmsRetention()) || !positive(authentication.getChallengeTtl())
                || !positive(authentication.getPasswordResetTicketTtl()) || !positive(authentication.getSmsResendCooldown())
                || !positive(authentication.getLoginFailureWindow()) || !positive(authentication.getLoginLockDuration())
                || !positive(authentication.getChallengeRetention()) || !positive(authentication.getSessionRetention())
                || !positive(authentication.getSecurityEventRetention())) {
            throw new IllegalStateException("Authentication durations must be positive");
        }
        if (authentication.isMockSmsEnabled() && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("Mock SMS is only allowed in local or test profiles");
        }
        if (authentication.isEnabled() && (authentication.getPhoneHmacKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || authentication.getPrivateKey().isBlank() || authentication.getPublicKey().isBlank())) {
            throw new IllegalStateException("Enabled authentication requires RSA key pair and a 256-bit phone HMAC key");
        }
        if (authentication.isEnabled() && environment.acceptsProfiles(Profiles.of("local", "test"))
                && !authentication.isMockSmsEnabled()) {
            throw new IllegalStateException("Local and test authentication requires Mock SMS until a real gateway is approved");
        }
    }

    /**
     * 判断配置时长是否非空且严格大于零。
     * @param duration 待检查时长
     * @return 时长有效时为 true
     */
    private boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
