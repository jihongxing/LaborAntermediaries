package com.company.labor.foundation.web;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 配置统一 UTC 时钟、长整型 JSON 表示及 OpenAPI Bearer 认证声明。 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearer"))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class WebConfig {

    /**
     * 提供可注入的 UTC 系统时钟，便于使用方在测试中替换时间来源。
     * @return UTC 系统时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 将 long 及 Long 输出为字符串，避免浏览器数字精度丢失；不改变数据库类型。
     * @return 应用于 Jackson 构建器的定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longSerialization() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(long.class, ToStringSerializer.instance);
    }
}
