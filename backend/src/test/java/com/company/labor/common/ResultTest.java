package com.company.labor.common;

import com.company.labor.common.constant.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Result单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("统一响应结果测试")
class ResultTest {

    @Test
    @DisplayName("成功响应（有数据）")
    void testSuccessWithData() {
        String data = "test data";
        Result<String> result = Result.success(data);

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("success", result.getMessage());
        assertEquals(data, result.getData());
        assertNotNull(result.getTimestamp());
        assertTrue(result.isSuccess());
        assertFalse(result.isError());
    }

    @Test
    @DisplayName("成功响应（无数据）")
    void testSuccessWithoutData() {
        Result<Void> result = Result.success();

        assertEquals(ErrorCode.SUCCESS, result.getCode());
        assertEquals("success", result.getMessage());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("失败响应（使用错误码默认消息）")
    void testErrorWithDefaultMessage() {
        Result<Void> result = Result.error(ErrorCode.EMPLOYEE_NOT_FOUND);

        assertEquals(ErrorCode.EMPLOYEE_NOT_FOUND, result.getCode());
        assertEquals("员工不存在", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
        assertTrue(result.isError());
    }

    @Test
    @DisplayName("失败响应（自定义消息）")
    void testErrorWithCustomMessage() {
        Result<Void> result = Result.error(ErrorCode.BAD_REQUEST, "自定义错误消息");

        assertEquals(ErrorCode.BAD_REQUEST, result.getCode());
        assertEquals("自定义错误消息", result.getMessage());
        assertNull(result.getData());
        assertTrue(result.isError());
    }

    @Test
    @DisplayName("参数错误快捷方法")
    void testBadRequest() {
        Result<Void> result = Result.badRequest("参数不能为空");

        assertEquals(ErrorCode.BAD_REQUEST, result.getCode());
        assertEquals("参数不能为空", result.getMessage());
    }

    @Test
    @DisplayName("未登录快捷方法")
    void testUnauthorized() {
        Result<Void> result = Result.unauthorized();

        assertEquals(ErrorCode.UNAUTHORIZED, result.getCode());
        assertEquals("未登录", result.getMessage());
    }

    @Test
    @DisplayName("无权限快捷方法")
    void testForbidden() {
        Result<Void> result = Result.forbidden();

        assertEquals(ErrorCode.FORBIDDEN, result.getCode());
        assertEquals("无权限", result.getMessage());
    }

    @Test
    @DisplayName("资源不存在快捷方法")
    void testNotFound() {
        Result<Void> result = Result.notFound();

        assertEquals(ErrorCode.NOT_FOUND, result.getCode());
        assertEquals("资源不存在", result.getMessage());
    }

    @Test
    @DisplayName("服务器错误快捷方法")
    void testServerError() {
        Result<Void> result = Result.serverError();

        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, result.getCode());
        assertEquals("服务器错误", result.getMessage());
    }
}
