package com.company.labor.common.exception;

import com.company.labor.common.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException exception) {
        int code = exception.getCode();
        int status = code >= 400 && code <= 599 ? code : 422;
        return ResponseEntity.status(status).body(Result.error(code, exception.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> validation(BindException exception) {
        String fields = exception.getFieldErrors().stream()
                .map(error -> error.getField()).distinct().sorted().reduce((first, second) -> first + ", " + second).orElse("input");
        return error(400, "Invalid fields: " + fields);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<Result<Void>> badInput(Exception exception) {
        return error(400, "Invalid request parameters or body");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> forbidden(AccessDeniedException exception) {
        return error(403, "Permission denied");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> notFound(NoResourceFoundException exception) {
        return error(404, "Resource not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return error(405, "Method not allowed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> mediaType(HttpMediaTypeNotSupportedException exception) {
        return error(415, "Unsupported media type");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> tooLarge(MaxUploadSizeExceededException exception) {
        return error(413, "Upload exceeds size limit");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Result<Void>> conflict(Exception exception) {
        return error(409, "Data conflict; refresh and retry");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        log.error("Unhandled failure type={}", exception.getClass().getName());
        return error(500, "Internal server error");
    }

    private ResponseEntity<Result<Void>> error(int status, String message) {
        return ResponseEntity.status(status).body(Result.error(status, message));
    }
}
