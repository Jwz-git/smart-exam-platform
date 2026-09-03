package com.smartexam.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：由 Service 层在校验失败或业务状态不允许时抛出，携带对外返回的状态码和错误码。
 *
 * <p>之所以自带 {@link HttpStatus}，是为了让「参数不合法（400）」「越权（403）」「资源不存在（404）」
 * 「状态冲突（409）」这几类结果都由业务代码自己决定，而不是在 Controller 里逐个 if 判断再转换。
 * 统一由 {@link ApiExceptionHandler} 转成对外的 JSON 结构。
 *
 * <p>{@code code} 是稳定的机器可读标识（如 {@code PAPER_SCORE_MISMATCH}），前端可据此做特殊处理；
 * {@code message} 是可以直接展示给用户的中文说明。
 */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 对外返回的 HTTP 状态码。 */
    public HttpStatus status() { return status; }

    /** 机器可读的错误码，供前端判断具体失败原因。 */
    public String code() { return code; }
}
