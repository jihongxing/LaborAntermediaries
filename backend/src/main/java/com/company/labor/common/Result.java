package com.company.labor.common;

import com.company.labor.common.constant.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果
 *
 * <p>所有API接口必须返回此格式，保证前端统一处理。
 *
 * <p>成功响应示例：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": { ... },
 *   "timestamp": 1697520000000
 * }
 * </pre>
 *
 * <p>失败响应示例：
 * <pre>
 * {
 *   "code": 1001,
 *   "message": "员工不存在",
 *   "data": null,
 *   "timestamp": 1697520000000
 * }
 * </pre>
 *
 * <p>使用示例：
 * <pre>
 * // 成功（有数据）
 * return Result.success(employee);
 *
 * // 成功（无数据）
 * return Result.success();
 *
 * // 失败（默认消息）
 * return Result.error(ErrorCode.EMPLOYEE_NOT_FOUND);
 *
 * // 失败（自定义消息）
 * return Result.error(ErrorCode.BAD_REQUEST, "员工姓名不能为空");
 * </pre>
 *
 * @param <T> 数据类型
 * @author 技术团队
 * @since 2026-10-18
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 响应码（200成功，其他失败）
     */
    private Integer code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 时间戳（毫秒）
     */
    private Long timestamp;

    /**
     * 私有构造函数
     */
    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 私有构造函数
     *
     * @param code 响应码
     * @param message 响应消息
     * @param data 响应数据
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 成功响应 ====================

    /**
     * 成功（有数据）
     *
     * @param data 响应数据
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS, "success", data);
    }

    /**
     * 成功（无数据）
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS, "success", null);
    }

    /**
     * 成功（自定义消息）
     *
     * @param message 响应消息
     * @param data 响应数据
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ErrorCode.SUCCESS, message, data);
    }

    // ==================== 失败响应 ====================

    /**
     * 失败（使用错误码默认消息）
     *
     * @param code 错误码
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> error(int code) {
        return new Result<>(code, ErrorCode.getMessage(code), null);
    }

    /**
     * 失败（自定义消息）
     *
     * @param code 错误码
     * @param message 错误消息
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败（自定义消息和数据）
     *
     * @param code 错误码
     * @param message 错误消息
     * @param data 响应数据
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message, data);
    }

    // ==================== 快捷方法 ====================

    /**
     * 参数错误
     *
     * @param message 错误消息
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> badRequest(String message) {
        return new Result<>(ErrorCode.BAD_REQUEST, message, null);
    }

    /**
     * 未登录
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> unauthorized() {
        return new Result<>(ErrorCode.UNAUTHORIZED, "未登录", null);
    }

    /**
     * 无权限
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> forbidden() {
        return new Result<>(ErrorCode.FORBIDDEN, "无权限", null);
    }

    /**
     * 资源不存在
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> notFound() {
        return new Result<>(ErrorCode.NOT_FOUND, "资源不存在", null);
    }

    /**
     * 服务器错误
     *
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> serverError() {
        return new Result<>(ErrorCode.INTERNAL_SERVER_ERROR, "服务器错误", null);
    }

    /**
     * 服务器错误（自定义消息）
     *
     * @param message 错误消息
     * @param <T> 数据类型
     * @return Result
     */
    public static <T> Result<T> serverError(String message) {
        return new Result<>(ErrorCode.INTERNAL_SERVER_ERROR, message, null);
    }

    // ==================== 判断方法 ====================

    /**
     * 是否成功
     *
     * @return true成功，false失败
     */
    public boolean isSuccess() {
        return ErrorCode.SUCCESS == this.code;
    }

    /**
     * 是否失败
     *
     * @return true失败，false成功
     */
    public boolean isError() {
        return !isSuccess();
    }
}
