package com.company.labor.common.filter;

import com.company.labor.common.context.UserContext;
import com.company.labor.foundation.context.ExecutionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为请求设置日志关联标识，并在请求前后清理线程上下文。
 *
 * <p>关联标识只用于追踪，不能作为可信身份或权限依据。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /**
     * 接受符合白名单的请求标识，否则生成 UUID；记录耗时并清理当前线程。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应，写入 X-Request-Id 响应头
     * @param chain 后续过滤器链
     * @throws ServletException 后续链处理请求失败
     * @throws IOException 后续链读写失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 限制字符集和长度，避免任意请求头进入日志关联字段。
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        // 在线程池复用前清除上一请求可能遗留的身份与组织信息。
        UserContext.clear();
        ExecutionContext.clear();
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 正常完成或抛出异常都释放身份上下文及当前请求的 MDC 字段。
            log.info("http method={} status={} durationMs={}",
                    request.getMethod(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            UserContext.clear();
            ExecutionContext.clear();
            MDC.remove("requestId");
            MDC.remove("correlationId");
        }
    }
}
