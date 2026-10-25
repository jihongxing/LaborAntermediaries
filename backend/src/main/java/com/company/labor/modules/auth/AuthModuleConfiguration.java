package com.company.labor.modules.auth;

import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.crypto.CryptoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;

/** 在认证明确启用后装配密码和认证秘密处理能力，避免默认配置误开放账号端点。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class AuthModuleConfiguration {
    /**
     * 构造 Spring Security 的 Argon2id 编码器，使用库维护的安全默认参数。
     * @return Argon2id 密码编码器
     */
    @Bean
    public PasswordEncoder authPasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * 包装 Argon2id 编码器并执行密码长度与弱口令校验。
     * @param encoder Argon2id 编码器
     * @return 认证模块密码凭据编码服务
     */
    @Bean
    public PasswordCredentialEncoder passwordCredentialEncoder(PasswordEncoder encoder) {
        return new PasswordCredentialEncoder(encoder);
    }

    /**
     * 装配独立 HMAC 摘要、手机号字段加密和安全随机凭证生成服务。
     * @param properties 全局认证配置
     * @param crypto 已配置的字段加密服务
     * @return 认证模块密码学工具
     */
    @Bean
    public AuthCryptography authCryptography(FoundationProperties properties, CryptoService crypto) {
        return new AuthCryptography(properties.getAuthentication(), crypto);
    }

    /**
     * 从受控资源读取本地 RS256 密钥对，密钥文件内容不会进入配置值或日志。
     * @param properties 全局认证配置
     * @param resources Spring 资源加载器
     * @param clock 可替换 UTC 时钟
     * @return 本地 Access Token 签发服务
     * @throws IOException 密钥资源无法读取或解析
     */
    @Bean
    public LocalTokenIssuer localTokenIssuer(FoundationProperties properties, ResourceLoader resources,
                                             java.time.Clock clock) throws IOException {
        return new LocalTokenIssuer(properties.getAuthentication(), resources, clock);
    }
}
