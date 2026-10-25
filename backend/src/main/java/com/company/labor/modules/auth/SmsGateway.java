package com.company.labor.modules.auth;

import java.time.Instant;

/** 短信发送边界；实现不得记录验证码或手机号明文。 */
public interface SmsGateway {
    /**
     * 发送用途受限的短时验证码。
     * @param challengeId 服务端生成的挑战编号
     * @param normalizedPhone 已规范化的大陆手机号
     * @param code 六位验证码，只能交付给已配置网关
     * @param expiresAt 挑战失效时间
     */
    void send(String challengeId, String normalizedPhone, String code, Instant expiresAt);
}
