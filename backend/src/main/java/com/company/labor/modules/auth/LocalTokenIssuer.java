package com.company.labor.modules.auth;

import com.company.labor.foundation.FoundationProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 使用独立部署 RSA 私钥签发短期 RS256 Access Token，不在令牌中存储客户端自报权限。 */
public final class LocalTokenIssuer {
    /** 本地认证发行方和会话时效。 */
    private final FoundationProperties.Authentication settings;
    /** 本地认证签名私钥。 */
    private final RSAPrivateKey privateKey;
    /** 与私钥配对的公钥，用于启动时验证密钥一致性和资源服务器解码。 */
    private final RSAPublicKey publicKey;
    /** 可替换 UTC 时钟。 */
    private final Clock clock;

    /**
     * 从受控 PEM 资源加载 RSA 私钥与公钥。
     * @param settings 本地认证设置
     * @param resources Spring 资源加载器
     * @param clock 可替换 UTC 时钟
     * @throws IOException 密钥资源读取或格式错误
     */
    public LocalTokenIssuer(FoundationProperties.Authentication settings, ResourceLoader resources, Clock clock)
            throws IOException {
        this.settings = settings;
        this.clock = clock;
        try (InputStream privateInput = resources.getResource(settings.getPrivateKey()).getInputStream();
             InputStream publicInput = resources.getResource(settings.getPublicKey()).getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            publicKey = RsaKeyConverters.x509().convert(publicInput);
        }
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("Authentication RSA keys do not form a pair");
        }
    }

    /**
     * 签发绑定当前 Refresh Session 的最小权限个人上下文令牌。
     * @param accountId 已确认处于 ACTIVE 的账号编号
     * @param sessionId 已持久化 Refresh Session 编号
     * @return RS256 签名的紧凑 JWT
     * @throws IllegalStateException RSA 签名失败或账号标识无效
     */
    public String issue(long accountId, String sessionId) {
        return issue(accountId, sessionId, "PERSONAL", null, Set.of("organization:create", "organization:membership:request"), 1);
    }

    /**
     * 签发绑定服务端持久会话版本和组织授权快照的访问令牌。
     * @param accountId 已确认处于 ACTIVE 的账号编号
     * @param sessionId 已持久化 Refresh Session 编号
     * @param contextType PERSONAL 或 ORGANIZATION
     * @param tenantId 服务端确认的组织编号，个人上下文为空
     * @param permissions 服务端根据成员岗位计算的权限
     * @param contextVersion 当前会话上下文版本
     * @return RS256 签名的紧凑 JWT
     * @throws IllegalArgumentException 上下文或版本无效
     * @throws IllegalStateException RSA 签名失败
     */
    public String issue(long accountId, String sessionId, String contextType, Long tenantId,
                        Set<String> permissions, int contextVersion) {
        if (accountId <= 0 || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("A valid account and session are required");
        }
        if (!("PERSONAL".equals(contextType) && tenantId == null
                || "ORGANIZATION".equals(contextType) && tenantId != null && tenantId > 0)
                || contextVersion < 1 || permissions == null) {
            throw new IllegalArgumentException("A valid authorization context is required");
        }
        Instant now = clock.instant();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(settings.getIssuer())
                .audience(List.of(settings.getAudience()))
                .subject(Long.toString(accountId))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plus(settings.getAccessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .claim("session_id", sessionId)
                .claim("context_type", contextType)
                .claim("context_version", contextVersion)
                .claim("permissions", permissions.stream().sorted().toList());
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Local access token signing failed", exception);
        }
    }

    /**
     * 返回与当前认证私钥配对的 RSA 公钥供令牌校验器配置使用。
     * @return 不含私钥材料的 RSA 公钥
     */
    public RSAPublicKey publicKey() {
        return publicKey;
    }
}
