package com.company.labor.modules.auth;

import com.company.labor.common.Result;
import com.company.labor.common.error.PlatformError;
import com.company.labor.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 仅 local/test 且连接来自 loopback 时允许通过挑战 ID 查看 Mock 短信。 */
@RestController
@RequestMapping("/auth/dev/mock-sms")
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "foundation.authentication", name = "enabled", havingValue = "true")
public class MockSmsController {
    /** 本地开发短信收件箱。 */
    private final MockSmsMailbox mailbox;

    /**
     * 创建本地验证码查询端点。
     * @param mailbox profile 限定的内存短信收件箱
     */
    public MockSmsController(MockSmsMailbox mailbox) {
        this.mailbox = mailbox;
    }

    /**
     * 通过服务端挑战编号查询尚未过期的 Mock 短信，仅接受本机回环连接。
     * @param challengeId 注册/登录/找回接口返回的挑战编号
     * @param request 当前网络请求
     * @return 脱敏手机号、验证码和失效时间
     * @throws BusinessException 非回环请求或消息不存在/过期
     */
    @GetMapping("/{challengeId}")
    public Result<MockSmsMailbox.Message> get(@PathVariable String challengeId, HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        MockSmsMailbox.Message message = mailbox.get(challengeId);
        if (message == null) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        return Result.success(message);
    }

    /**
     * 判断客户端地址是否为 IP loopback，不接受代理头覆盖的来源地址。
     * @param address Servlet 容器识别的连接地址
     * @return IPv4 或 IPv6 回环地址返回 true
     */
    private boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
