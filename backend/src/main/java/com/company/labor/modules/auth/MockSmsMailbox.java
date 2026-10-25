package com.company.labor.modules.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** local/test 内存短信收件箱；短信正文不落库、不写日志，进程退出后即清除。 */
@Component
@Profile({"local", "test"})
public class MockSmsMailbox {
    /** 按挑战编号索引的内存消息，使用并发容器支持本地多请求联调。 */
    private final Map<String, Message> messages = new ConcurrentHashMap<>();
    /** 可替换的系统时钟，便于验证过期处理。 */
    private final Clock clock;
    /** 最长保留时间，避免邮箱消息超过当前挑战有效期后仍可查询。 */
    private final Duration retention;

    /**
     * 创建仅用于开发和测试的进程内收件箱。
     * @param clock UTC 时钟
     * @param properties 配置的最大消息保留期限
     */
    public MockSmsMailbox(Clock clock, com.company.labor.foundation.FoundationProperties properties) {
        this.clock = clock;
        this.retention = properties.getAuthentication().getMockSmsRetention();
    }

    /**
     * 保存验证码的临时可查询副本，过期时间不晚于挑战或配置保留期。
     * @param challengeId 服务端挑战编号
     * @param normalizedPhone 已规范化手机号，仅供开发界面遮蔽展示
     * @param code 六位验证码
     * @param challengeExpiresAt 挑战有效期截止点
     */
    public void put(String challengeId, String normalizedPhone, String code, Instant challengeExpiresAt) {
        Instant retentionEnd = clock.instant().plus(retention);
        Instant expiresAt = challengeExpiresAt.isBefore(retentionEnd) ? challengeExpiresAt : retentionEnd;
        messages.put(challengeId, new Message(mask(normalizedPhone), code, expiresAt));
        removeExpired();
    }

    /**
     * 按挑戰编号读取未过期的 Mock 短信内容。
     * @param challengeId 服务端返回的挑战编号
     * @return 短信内容，不存在或过期时返回 null
     */
    public Message get(String challengeId) {
        Message message = messages.get(challengeId);
        if (message != null && !message.expiresAt().isAfter(clock.instant())) {
            messages.remove(challengeId, message);
            return null;
        }
        return message;
    }

    /**
     * 删除超过当前时间的消息，限制长期运行的开发进程内存增长。
     */
    private void removeExpired() {
        Instant now = clock.instant();
        messages.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    /**
     * 对手机号中间位脱敏，只用于 Mock 收件箱显示。
     * @param phone 规范化手机号
     * @return 保留区号和末四位的展示字符串
     */
    private String mask(String phone) {
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 4);
    }

    /** Mock 短信只在进程内保存的受限视图。 */
    public record Message(String maskedPhone, String code, Instant expiresAt) {
        /**
         * @param maskedPhone 脱敏手机号
         * @param code 短信验证码，仅能从 local/test 查询接口或测试注入取得
         * @param expiresAt 收件箱副本的失效时间
         */
    }
}
