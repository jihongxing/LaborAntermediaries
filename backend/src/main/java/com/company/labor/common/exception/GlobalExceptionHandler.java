package com.company.labor.common.exception;

import com.company.labor.common.Result;
import com.company.labor.common.error.PlatformError;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 将 MVC 层异常转换为统一 JSON 错误响应，避免回传数据库和内部异常详情。
 *
 * <p>不负责安全过滤器链中的异常响应，该链由专用错误写入器处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 使用异常声明的 HTTP 状态，兼容历史数字码构造方式。
     *
     * @param exception 业务异常，其消息会直接回传，抛出方必须保证可公开
     * @return 包含业务码及安全业务消息的响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException exception) {
        Result<Void> result = Result.error(exception.getCode(), exception.getMessage());
        result.setErrorKey(exception.getErrorKey());
        result.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(exception.getHttpStatus()).body(result);
    }

    /**
     * 返回去重并排序的错误字段名，不暴露被拒绝的值或校验消息。
     *
     * @param exception 参数绑定或字段校验异常
     * @return HTTP 400 响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> validation(BindException exception) {
        String fields = exception.getFieldErrors().stream()
                .map(error -> error.getField()).distinct().sorted().reduce((first, second) -> first + ", " + second).orElse("input");
        return error(PlatformError.INVALID_ARGUMENT, "Invalid fields: " + fields);
    }

    /**
     * 将参数、请求体解析及方法校验失败统一转换为通用提示。
     *
     * @param exception 输入异常，不向客户端回传其原始消息
     * @return HTTP 400 响应
     */
    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<Result<Void>> badInput(Exception exception) {
        return error(PlatformError.INVALID_ARGUMENT);
    }

    /**
     * 处理进入 MVC 异常处理流程的权限拒绝。
     *
     * @param exception 权限不足异常
     * @return HTTP 403 响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden(AccessDeniedException exception) {
        return error(PlatformError.ACCESS_DENIED);
    }

    /**
     * 处理未找到的 Web 资源。
     *
     * @param exception 资源查找异常
     * @return HTTP 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> notFound(NoResourceFoundException exception) {
        return error(PlatformError.RESOURCE_NOT_FOUND);
    }

    /**
     * 处理资源不支持的 HTTP 方法。
     *
     * @param exception 请求方法异常
     * @return HTTP 405 响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return error(PlatformError.METHOD_NOT_ALLOWED);
    }

    /**
     * 处理不支持的请求媒体类型。
     *
     * @param exception 媒体类型异常
     * @return HTTP 415 响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> mediaType(HttpMediaTypeNotSupportedException exception) {
        return error(PlatformError.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 处理超过上传大小限制的请求。
     *
     * @param exception 上传大小异常
     * @return HTTP 413 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> tooLarge(MaxUploadSizeExceededException exception) {
        return error(PlatformError.PAYLOAD_TOO_LARGE);
    }

    /**
     * 将约束冲突和乐观锁失败映射为通用冲突提示，不暴露 SQL。
     *
     * @param exception 数据约束或并发更新异常
     * @return HTTP 409 响应
     */
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Result<Void>> conflict(Exception exception) {
        return error(PlatformError.DATA_CONFLICT);
    }

    /**
     * 兜底处理未分类异常，只记录异常类型，不记录敏感消息或请求内容。
     *
     * @param exception 未处理异常
     * @return 不含内部细节的 HTTP 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        log.error("Unhandled failure type={}", exception.getClass().getName());
        return error(PlatformError.INTERNAL_ERROR);
    }

    /**
     * 使用默认安全消息构建契约化错误响应。
     *
     * @param error 平台错误定义
     * @return 统一错误响应
     */
    private ResponseEntity<Result<Void>> error(PlatformError error) {
        return error(error, error.message());
    }

    /**
     * 构建错误码与 HTTP 状态分离、带请求关联标识的响应。
     * @param error 平台错误定义
     * @param message 已脱敏的公开提示，不使用异常原始消息
     * @return 统一错误响应
     */
    private ResponseEntity<Result<Void>> error(PlatformError error, String message) {
        Result<Void> result = Result.error(error);
        result.setMessage(message);
        result.setRequestId(MDC.get("requestId"));
        return ResponseEntity.status(error.httpStatus()).body(result);
    }
}
