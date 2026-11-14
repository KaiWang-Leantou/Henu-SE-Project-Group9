package com.henu.whiteboard.common;

/**
 * 业务异常：携带统一错误码，由全局异常处理器转成 {code,message,data:null} 信封。
 */
public class ApiException extends RuntimeException {

    private final ApiCode code;

    public ApiException(ApiCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ApiCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ApiCode code() {
        return code;
    }
}
