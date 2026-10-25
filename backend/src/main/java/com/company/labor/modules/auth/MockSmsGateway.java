package com.company.labor.modules.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** local/test Mock 网关；正式 API 不返回验证码，收件箱服务仅本地和测试 profile 可见。 */
@Component
@Profile({"local", "test"})
public class MockSmsGateway implements SmsGateway {
    /** 保存临时消息的 profile 限定收件箱。 */
    private final MockSmsMailbox mailbox;

    /**
     * 注入本地 Mock 收件箱。
     * @param mailbox local/test 内存收件箱
     */
    public MockSmsGateway(MockSmsMailbox mailbox) {
        this.mailbox = mailbox;
    }

    /**
     * 将短信内容放入仅进程内可查询的开发收件箱，不输出消息正文。
     * @param challengeId 服务端挑战编号
     * @param normalizedPhone 已规范化手机号
     * @param code 六位验证码
     * @param expiresAt 挑战失效时间
     */
    @Override
    public void send(String challengeId, String normalizedPhone, String code, Instant expiresAt) {
        mailbox.put(challengeId, normalizedPhone, code, expiresAt);
    }
}
