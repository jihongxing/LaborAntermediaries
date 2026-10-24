package com.company.labor.foundation.web;

import com.company.labor.common.error.PlatformError;

import com.company.labor.foundation.security.HttpErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/** 在下游解析 JSON 之前限制请求体大小，并用可重读的同步流包装已缓存内容。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JsonBodyLimitFilter extends OncePerRequestFilter {
    /** JSON 请求体上限为 1 MiB，单位字节，与 multipart 文件限制分别生效。 */
    public static final int MAX_BYTES = 1024 * 1024;
    /** 输出统一 413 JSON 错误的写入器。 */
    private final HttpErrorWriter errors;

    /**
     * 创建 JSON 请求体大小过滤器。
     * @param errors 过滤器层错误响应写入器
     */
    public JsonBodyLimitFilter(HttpErrorWriter errors) {
        this.errors = errors;
    }

    /**
     * 放行非 JSON 请求；对 JSON 同时校验声明长度和实际读取长度。
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后续过滤器链
     * @throws ServletException 下游处理失败
     * @throws IOException 请求体读取或响应写入失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (type == null || !(type.toLowerCase(Locale.ROOT).contains("/json")
                || type.toLowerCase(Locale.ROOT).contains("+json"))) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            errors.write(response, PlatformError.PAYLOAD_TOO_LARGE);
            return;
        }
        // 不能只相信 Content-Length；额外读取一个字节以识别未知长度或分块请求的超限。
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            errors.write(response, PlatformError.PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            /**
             * 使用请求字符集读取缓存，未声明时采用 UTF-8。
             * @return 基于缓存请求体的字符读取器
             * @throws IOException 字符集不受支持或流创建失败
             */
            @Override
            public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(),
                        getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding()));
            }

            /**
             * 为下游提供独立的缓存流，不再次读取已经消耗的原始请求流。
             * @return 支持同步读取的 Servlet 输入流
             */
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    /**
                     * 读取下一个缓存字节。
                     * @return 0 至 255 的字节值，结束时为 -1
                     */
                    @Override
                    public int read() {
                        return input.read();
                    }

                    /**
                     * 判断缓存是否已经读完。
                     * @return 没有剩余字节时为 true
                     */
                    @Override
                    public boolean isFinished() {
                        return input.available() == 0;
                    }

                    /**
                     * 表示内存缓存可立即同步读取。
                     * @return 始终为 true
                     */
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    /**
                     * 明确拒绝非阻塞读取，不能将此包装器用于 Servlet 异步读取场景。
                     * @param listener 非阻塞读取监听器
                     * @throws UnsupportedOperationException 此缓存流不支持监听器
                     */
                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Non-blocking JSON body reads are not supported");
                    }
                };
            }
        }, response);
    }
}
