package com.company.labor.common.exception;

import com.company.labor.common.Result;
import com.company.labor.common.constant.ErrorCode;
import com.company.labor.common.util.DesensitizeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p>捕获所有Controller层抛出的异常，统一转换为Result格式返回给前端。
 *
 * <p>处理的异常类型：
 * <ul>
 *   <li>BusinessException - 业务异常（返回错误码和消息）</li>
 *   <li>MethodArgumentNotValidException - 参数校验异常（@Valid）</li>
 *   <li>BindException - 参数绑定异常</li>
 *   <li>IllegalArgumentException - 非法参数异常</li>
 *   <li>Exception - 其他未知异常（返回500）</li>
 * </ul>
 *
 * <p>安全要求：
 * <ul>
 *   <li>日志中不能输出密码、身份证、银行卡等敏感信息</li>
 *   <li>生产环境不能返回详细的异常堆栈给前端</li>
 *   <li>所有异常都要记录日志，方便排查问题</li>
 * </ul>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     *
     * @param e 业务异常
     * @return Result
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        // 业务异常属于预期内的异常，只记录WARN级别日志
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常（@Valid）
     *
     * @param e 参数校验异常
     * @return Result
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        // 获取所有字段错误
        String errorMessage = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));

        log.warn("参数校验失败：{}", errorMessage);
        return Result.badRequest(errorMessage);
    }

    /**
     * 处理参数绑定异常
     *
     * @param e 参数绑定异常
     * @return Result
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        // 获取所有字段错误
        String errorMessage = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));

        log.warn("参数绑定失败：{}", errorMessage);
        return Result.badRequest(errorMessage);
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return Result
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return Result.badRequest(e.getMessage());
    }

    /**
     * 处理其他未知异常
     *
     * @param e 异常
     * @return Result
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 未知异常属于非预期异常，记录ERROR级别日志
        log.error("系统异常：{}", e.getMessage(), e);

        // 生产环境不返回详细的异常信息给前端（防止泄露敏感信息）
        return Result.serverError("系统繁忙，请稍后再试");
    }

    /**
     * 格式化字段错误消息
     *
     * @param fieldError 字段错误
     * @return 格式化后的消息
     */
    private String formatFieldError(FieldError fieldError) {
        String field = fieldError.getField();
        String message = fieldError.getDefaultMessage();
        Object rejectedValue = fieldError.getRejectedValue();

        // 脱敏敏感字段
        String valueStr = desensitizeIfNeeded(field, rejectedValue);

        return String.format("%s%s（当前值：%s）", field, message, valueStr);
    }

    /**
     * 如果是敏感字段，进行脱敏处理
     *
     * @param field 字段名
     * @param value 字段值
     * @return 脱敏后的值
     */
    private String desensitizeIfNeeded(String field, Object value) {
        if (value == null) {
            return "null";
        }

        String valueStr = value.toString();

        // 密码字段不输出任何信息
        if (field.toLowerCase().contains("password")) {
            return "***";
        }

        // 身份证字段脱敏
        if (field.toLowerCase().contains("idcard")) {
            return DesensitizeUtil.idCard(valueStr);
        }

        // 手机号字段脱敏
        if (field.toLowerCase().contains("mobile") || field.toLowerCase().contains("phone")) {
            return DesensitizeUtil.mobile(valueStr);
        }

        // 银行卡字段脱敏
        if (field.toLowerCase().contains("bankcard")) {
            return DesensitizeUtil.bankCard(valueStr);
        }

        return valueStr;
    }
}
