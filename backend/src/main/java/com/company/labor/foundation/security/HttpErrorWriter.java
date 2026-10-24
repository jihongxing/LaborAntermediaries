package com.company.labor.foundation.security;

import com.company.labor.common.Result;
import com.company.labor.common.error.ErrorDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 在安全过滤器等 MVC 异常处理之外的入口输出统一 JSON 错误响应。 */
@Component
public class HttpErrorWriter {
    /** 与应用统一配置的 JSON 序列化器。 */
    private final ObjectMapper mapper;

    /**
     * 创建错误响应写入器。
     * @param mapper JSON 序列化器
     */
    public HttpErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 设置 HTTP 状态并输出 UTF-8 错误体；调用方应确保响应尚未提交。
     * @param response 当前响应
     * @param status HTTP 状态，同时作为统一错误码
     * @param message 可对外披露的错误信息，不得携带内部异常或敏感内容
     * @throws IOException 写入响应失败
     */
    public void write(HttpServletResponse response, int status, String message) throws IOException {
        Result<Void> result = Result.error(status, message);
        result.setRequestId(MDC.get("requestId"));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), result);
    }

    /**
     * 在过滤器链中输出与 MVC 相同的错误契约，不把数字业务码误用为 HTTP 状态。
     * @param response 尚未提交的响应
     * @param error 错误定义，使用其默认安全消息
     * @throws IOException 响应写入失败
     */
    public void write(HttpServletResponse response, ErrorDefinition error) throws IOException {
        Result<Void> result = Result.error(error);
        result.setRequestId(MDC.get("requestId"));
        response.setStatus(error.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), result);
    }
}
