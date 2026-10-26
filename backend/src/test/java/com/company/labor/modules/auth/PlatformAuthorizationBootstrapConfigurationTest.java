package com.company.labor.modules.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证首个授权引导默认关闭，且只能由明确的离线 Spring 启动方式触发。 */
class PlatformAuthorizationBootstrapConfigurationTest {
    /** 验证 Spring 条件装配而不创建真实网络服务器。 */
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(PlatformAuthorizationBootstrapConfiguration.class);

    /** 验证启用标志不能在默认或 Web 应用模式下注册引导执行器。 */
    @Test
    void bootstrapRunnerIsAbsentUnlessExplicitlyEnabledInOfflineMode() {
        contexts.withPropertyValues("platform.authorization.bootstrap.enabled=true",
                        "spring.main.web-application-type=servlet")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
        contexts.withPropertyValues("spring.main.web-application-type=none")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
    }

    /** 验证离线执行器把外部显式提供的引导资料传给一次性授权服务。 */
    @Test
    void offlineRunnerPassesExplicitBootstrapArgumentsToAuthorizationService() {
        PlatformAuthorizationService authorizations = mock(PlatformAuthorizationService.class);
        Instant expiresAt = Instant.parse("2027-01-01T00:00:00Z");
        when(authorizations.bootstrap(eq(99111L), eq(122L), eq(expiresAt), eq("受控离线初始化")))
                .thenReturn(73001L);

        contexts.withBean(PlatformAuthorizationService.class, () -> authorizations)
                .withPropertyValues("platform.authorization.bootstrap.enabled=true",
                        "spring.main.web-application-type=none",
                        "platform.authorization.bootstrap.operator-id=99111",
                        "platform.authorization.bootstrap.target-account-id=122",
                        "platform.authorization.bootstrap.expires-at=2027-01-01T00:00:00Z",
                        "platform.authorization.bootstrap.reason=受控离线初始化")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                    ApplicationRunner runner = context.getBean(ApplicationRunner.class);
                    ApplicationArguments arguments = new DefaultApplicationArguments();
                    assertDoesNotThrow(() -> runner.run(arguments));
                    verify(authorizations).bootstrap(99111L, 122L, expiresAt, "受控离线初始化");
                });
    }
}
