package com.henu.whiteboard.common;

/**
 * 统一返回信封 {code,message,data}；成功 code 固定 OK，失败 data 为 null。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>("OK", "ok", null);
    }

    public static ApiResponse<Void> error(ApiCode code, String message) {
        return new ApiResponse<>(code.name(), message, null);
    }
}
