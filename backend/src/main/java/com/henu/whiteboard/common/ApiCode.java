package com.henu.whiteboard.common;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码表：字符串 code 与 HTTP 状态码一一对应，前端可直接按 code 分支。
 */
public enum ApiCode {

    OK(HttpStatus.OK),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 归档/回收站白板拒绝写入（WS 用字符串码，REST 统一按 403 返回）。 */
    READ_ONLY(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    USERNAME_TAKEN(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ApiCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
