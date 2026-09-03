package com.smartexam.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 全局异常处理：把各类异常统一转成 {@code docs/api.md} 约定的错误结构
 * {@code {code, message, fieldErrors, requestId}}。
 *
 * <p>处理器的排列顺序不影响匹配——Spring 总是选最具体的类型，
 * 因此下方的 {@code Exception} 兜底只会接住前面没覆盖到的异常。
 *
 * <p>状态码归属：参数或业务状态不合法 400，凭证无效 401，越权 403，
 * 资源不存在 404，唯一约束或状态冲突 409，其余 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 请求体字段校验失败（{@code @Valid} 触发），逐字段返回原因。
     *
     * <p>用 {@code putIfAbsent} 是因为同一字段可能同时违反多条约束，只保留第一条即可，
     * 避免前端在一个输入框下面堆叠多行提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", fields);
    }

    /** 查询参数和路径变量上的约束校验失败：例如 page=0、size=101。 */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> constraintViolation(ConstraintViolationException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        });
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", fields);
    }

    /** Spring 6.1 起方法参数校验也可能抛出该异常，统一按 400 返回。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> methodValidation(HandlerMethodValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", Map.of());
    }

    /** 查询参数或路径变量无法转换成目标类型，例如 type=NOT_A_TYPE、page=abc。 */
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<Map<String, Object>> typeMismatch(TypeMismatchException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            fields.put(mismatch.getName(), "取值不合法");
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", fields);
    }

    /** 请求体不是合法 JSON 或枚举值非法。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "请求体格式不正确或字段取值非法", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限执行此操作", Map.of());
    }

    /** 登录失败或令牌对应账号已失效，返回 401。 */
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<Map<String, Object>> badCredentials(BadCredentialsException exception) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), Map.of());
    }

    /** 业务异常自带状态码和错误码，原样透出。 */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Map<String, Object>> domain(DomainException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), Map.of());
    }

    /**
     * 兜底处理：未预期的异常也按统一错误结构返回，不把堆栈暴露给调用方。
     * Spring MVC 自带的异常（参数类型不匹配、请求方法不支持等）实现了 ErrorResponse 并已带有正确的
     * 客户端错误状态码，这里保留它们的状态码，只是套进统一结构，不能一律压成 500。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
            if (status.is4xxClientError()) {
                return status == HttpStatus.BAD_REQUEST
                        ? error(status, "VALIDATION_ERROR", "请求参数不合法", Map.of())
                        : error(status, status.name(), status.getReasonPhrase(), Map.of());
            }
        }
        log.error("未处理的服务端异常", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误，请稍后重试", Map.of());
    }

    /** 组装错误响应体。{@code requestId} 与成功响应一致，便于把用户截图和服务端日志对应起来。 */
    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message,
            Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message,
                "fieldErrors", fieldErrors,
                "requestId", UUID.randomUUID().toString()));
    }
}
