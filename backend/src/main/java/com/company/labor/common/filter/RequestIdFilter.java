package com.company.labor.common.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * RequestId过滤器
 *
 * <p>为每个请求生成唯一的request_id，并放入MDC（Mapped Diagnostic Context），
 * 在日志中自动输出，方便追踪整个请求链路。
 *
 * <p>工作流程：
 * <ol>
 *   <li>请求进入时，生成UUID作为request_id</li>
 *   <li>将request_id放入MDC</li>
 *   <li>日志中自动输出request_id（配置logback-spring.xml）</li>
 *   <li>请求结束后，清除MDC（防止内存泄漏）</li>
 * </ol>
 *
 * <p>日志格式（包含request_id）：
 * <pre>
 * 2026-10-18 10:30:00.123 [http-nio-8080-exec-1] [a1b2c3d4-e5f6-7890-abcd-ef1234567890] INFO  c.c.l.e.EmployeeService - 查询员工：id=1001
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
@Component
@Order(1)
public class RequestIdFilter implements Filter {

    /**
     * MDC中的request_id键名
     */
    private static final String REQUEST_ID_KEY = "requestId";

    /**
     * HTTP请求头中的request_id键名
     */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        try {
            // 1. 从请求头中获取request_id（如果前端传了）
            String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);

            // 2. 如果请求头中没有，则生成新的request_id
            if (requestId == null || requestId.isEmpty()) {
                requestId = generateRequestId();
            }

            // 3. 放入MDC（日志中自动输出）
            MDC.put(REQUEST_ID_KEY, requestId);

            // 4. 记录请求开始日志
            log.info("请求开始：method={}, uri={}, remoteAddr={}",
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI(),
                    getRemoteAddr(httpRequest));

            // 5. 继续执行后续过滤器和Controller
            chain.doFilter(request, response);

        } finally {
            // 6. 请求结束后清除MDC（防止内存泄漏）
            log.info("请求结束");
            MDC.remove(REQUEST_ID_KEY);
        }
    }

    /**
     * 生成request_id
     *
     * @return request_id（UUID）
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 获取客户端真实IP
     *
     * <p>考虑了代理和负载均衡的情况
     *
     * @param request HTTP请求
     * @return 客户端IP
     */
    private String getRemoteAddr(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For可能包含多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
