package com.company.labor.foundation;

import com.company.labor.foundation.crypto.CryptoService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

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
     * 校验所有环境的任务时间与跨域约束，并检查生产环境的安全配置。
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
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // 开发环境可不配安全材料；生产必须失败关闭，不能以降级方式绕过校验。
            if (properties.getSecurity().getPublicKey().isBlank() || !crypto.isConfigured()
                    || !properties.getStorage().getRoot().isAbsolute()) {
                throw new IllegalStateException("Production requires JWT verification, encryption and an absolute private storage path");
            }
        }
    }
}
