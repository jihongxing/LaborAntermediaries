package com.company.labor.modules.auth;

/**
 * 外部身份提供商 SPI；实现必须在服务端校验授权码受众、回调地址和提供商响应签名。
 * 不实现真实提供商时不得返回客户端传入的主体作为已验证身份。
 */
public interface IdentityProvider {
    /**
     * 返回稳定且大写的提供商标识，对应身份唯一键中的 provider。
     * @return 例如 WECHAT_MINIPROGRAM
     */
    String providerKey();

    /**
     * 在提供商服务端交换一次性授权码，统一身份主体由服务端解析。
     * @param authorizationCode 提供商签发的一次性授权码
     * @param redirectUri 与授权请求完全匹配的已登记回调地址；小程序可为空
     * @return 已经通过提供商验证的主体摘要输入
     * @throws IdentityProviderException 授权码无效、过期、受众不符或提供商不可达
     */
    VerifiedIdentity exchangeAuthorizationCode(String authorizationCode, String redirectUri)
            throws IdentityProviderException;

    /** 外部身份适配器验证成功后的主体；subject 不可由普通客户端直接提交。 */
    record VerifiedIdentity(String providerKey, String subject) {
        /**
         * @param providerKey 已验证的稳定提供商标识
         * @param subject 提供商返回的 openid、sub 或等价不可变主体
         */
        /**
         * 验证提供商与主体边界，避免适配器向身份表写入不受限的值。
         * @throws IllegalArgumentException 提供商或主体格式不合法
         */
        public VerifiedIdentity {
            if (providerKey == null || !providerKey.matches("[A-Z][A-Z0-9_]{1,39}")
                    || subject == null || subject.isBlank() || subject.length() > 512) {
                throw new IllegalArgumentException("A verified provider and bounded subject are required");
            }
        }
    }

    /** 外部身份适配器在无法验证授权码时抛出的安全边界异常。 */
    final class IdentityProviderException extends RuntimeException {
        /**
         * 创建不包含授权码或身份主体的适配器异常。
         * @param message 面向运维人员的非敏感原因
         */
        public IdentityProviderException(String message) {
            super(message);
        }
    }
}
