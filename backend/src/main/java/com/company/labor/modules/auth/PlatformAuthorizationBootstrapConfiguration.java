package com.company.labor.modules.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Instant;

/** 只在明确启用且 Spring Web 类型为 none 时提供一次性离线平台授权引导。 */
@Configuration
@ConditionalOnProperty(prefix = "platform.authorization.bootstrap", name = "enabled", havingValue = "true")
public class PlatformAuthorizationBootstrapConfiguration {
    /** 离线引导执行结果日志；不记录凭据或授权理由正文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformAuthorizationBootstrapConfiguration.class);

    /**
     * 注册受控离线引导任务；未显式指定非 Web 类型时不创建此执行器。
     * @param authorizations 平台岗位授权服务
     * @param environment 外部部署环境参数
     * @return Spring 启动后执行一次的离线任务
     */
    @Bean
    @ConditionalOnProperty(name = "spring.main.web-application-type", havingValue = "none")
    public ApplicationRunner platformAuthorizationBootstrapRunner(PlatformAuthorizationService authorizations,
                                                                   Environment environment) {
        return arguments -> {
            long operatorId = Long.parseLong(environment.getRequiredProperty(
                    "platform.authorization.bootstrap.operator-id"));
            long targetAccountId = Long.parseLong(environment.getRequiredProperty(
                    "platform.authorization.bootstrap.target-account-id"));
            Instant expiresAt = Instant.parse(environment.getRequiredProperty(
                    "platform.authorization.bootstrap.expires-at"));
            String reason = environment.getRequiredProperty("platform.authorization.bootstrap.reason");
            long grantId = authorizations.bootstrap(operatorId, targetAccountId, expiresAt, reason);
            LOGGER.info("Platform authorization bootstrap completed; grantId={}, targetAccountId={}",
                    grantId, targetAccountId);
        };
    }
}
