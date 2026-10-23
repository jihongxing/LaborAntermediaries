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

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
        long started = System.nanoTime();
        UserContext.clear();
        ExecutionContext.clear();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("http method={} status={} durationMs={}",
                    request.getMethod(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            UserContext.clear();
            ExecutionContext.clear();
            MDC.remove("requestId");
        }
    }
}
