package com.company.labor.foundation.web;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JsonBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 1024 * 1024;
    private final HttpErrorWriter errors;

    public JsonBodyLimitFilter(HttpErrorWriter errors) {
        this.errors = errors;
    }

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
            errors.write(response, 413, "JSON body exceeds 1 MiB");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            errors.write(response, 413, "JSON body exceeds 1 MiB");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(),
                        getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding()));
            }

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return input.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return input.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Non-blocking JSON body reads are not supported");
                    }
                };
            }
        }, response);
    }
}
