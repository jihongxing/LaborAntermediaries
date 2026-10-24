package com.company.labor.common.error;

import com.company.labor.common.Result;
import com.company.labor.common.exception.BusinessException;
import com.company.labor.common.exception.GlobalExceptionHandler;
import com.company.labor.foundation.security.HttpErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 验证新错误契约、旧数字码兼容及 MVC/安全过滤器响应的一致性。 */
class ErrorContractTest {
    /**
     * 用于模拟业务模块独立定义的错误，不依赖底座业务枚举。
     * @param code 数字业务码
     * @param key 稳定错误标识
     * @param httpStatus HTTP 状态
     * @param message 安全展示消息
     */
    private record ModuleError(int code, String key, int httpStatus, String message) implements ErrorDefinition {
    }

    /** 每个场景结束后清理请求关联标识，避免影响后续测试。 */
    @AfterEach
    void clear() {
        MDC.clear();
    }

    /** 平台错误码和标识必须唯一，且业务码不同于 HTTP 状态。 */
    @Test
    void catalogHasUniqueStableCodesAndKeys() {
        var codes = new HashSet<Integer>();
        var keys = new HashSet<String>();
        for (PlatformError error : PlatformError.values()) {
            error.validate();
            assertTrue(codes.add(error.code()));
            assertTrue(keys.add(error.key()));
            assertTrue(error.code() >= 100000 && error.code() <= 199999);
            assertNotEquals(error.code(), error.httpStatus());
        }
    }

    /** 新业务模块可以独立定义错误，客户端不依赖历史员工、工资等模块编码。 */
    @Test
    void modulesCanDefineTheirOwnErrors() {
        ErrorDefinition error = new ModuleError(300001, "AGREEMENT.VERSION_CONFLICT", 409, "合作版本已变化");
        BusinessException exception = new BusinessException(error, new IllegalStateException("internal secret"));
        var response = new GlobalExceptionHandler().business(exception);
        assertEquals(409, response.getStatusCode().value());
        assertEquals(300001, response.getBody().getCode());
        assertEquals(error.key(), response.getBody().getErrorKey());
        assertFalse(response.getBody().getMessage().contains("secret"));
        assertNotNull(exception.getCause());
    }

    /** 无效错误定义在构造异常或响应体时均被拒绝。 */
    @Test
    void invalidDefinitionsFailFast() {
        for (ErrorDefinition error : new ErrorDefinition[]{
                new ModuleError(409, "AGREEMENT.CONFLICT", 409, "conflict"),
                new ModuleError(300001, "unstable", 409, "conflict"),
                new ModuleError(300001, "AGREEMENT.CONFLICT", 200, "conflict"),
                new ModuleError(300001, "AGREEMENT.CONFLICT", 409, "")}) {
            assertThrows(IllegalArgumentException.class, () -> new BusinessException(error));
            assertThrows(IllegalArgumentException.class, () -> Result.error(error));
        }
    }

    /** 旧调用保留原业务码和原 HTTP 映射，不强制前端一次性迁移所有接口。 */
    @Test
    void legacyConstructorsRemainCompatible() {
        BusinessException historical = new BusinessException(1001, "legacy");
        assertEquals(1001, historical.getCode());
        assertEquals(422, historical.getHttpStatus());
        assertNull(historical.getErrorKey());
        assertEquals(403, new BusinessException(403, "denied").getHttpStatus());
        assertEquals(1001, new GlobalExceptionHandler().business(historical).getBody().getCode());
    }

    /** MVC 与过滤器使用相同业务码、错误标识、HTTP 状态和请求编号。 */
    @Test
    void filterAndMvcErrorsUseTheSameContract() throws Exception {
        MDC.put("requestId", "request-123");
        var mvc = new GlobalExceptionHandler().business(new BusinessException(PlatformError.ACCESS_DENIED));
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper mapper = new ObjectMapper();
        new HttpErrorWriter(mapper).write(response, PlatformError.ACCESS_DENIED);
        var body = mapper.readTree(response.getContentAsByteArray());
        assertEquals(mvc.getStatusCode().value(), response.getStatus());
        assertEquals(mvc.getBody().getCode().intValue(), body.path("code").asInt());
        assertEquals(mvc.getBody().getErrorKey(), body.path("errorKey").asText());
        assertEquals("request-123", body.path("requestId").asText());
        assertEquals("request-123", mvc.getBody().getRequestId());
    }

    /** 所有通用 HTTP 异常入口提供一致错误标识，并且兜底响应不泄露内部消息。 */
    @Test
    void genericHandlersExposeOnlySafeMessages() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        assertEquals(PlatformError.INVALID_ARGUMENT.key(),
                handler.badInput(new IllegalArgumentException("passport secret")).getBody().getErrorKey());
        assertEquals(PlatformError.DATA_CONFLICT.key(), handler.conflict(new RuntimeException("SQL")).getBody().getErrorKey());
        assertEquals(PlatformError.INTERNAL_ERROR.message(),
                handler.unexpected(new RuntimeException("secret")).getBody().getMessage());
    }
}
