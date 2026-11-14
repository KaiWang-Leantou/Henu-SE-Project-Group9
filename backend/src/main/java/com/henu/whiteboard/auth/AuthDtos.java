package com.henu.whiteboard.auth;

/**
 * 认证接口的请求/响应 DTO；字段名与 docs/05 §1 逐字对齐。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(String username, String password, String nickname) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record GuestRequest(String nickname) {
    }

    public record UpgradeRequest(String username, String password, String nickname) {
    }

    /** username 对游客为 null，必须原样出现在响应里。 */
    public record UserView(String id, String username, String nickname, boolean guest) {
    }

    public record TokenResponse(String token, UserView user) {
    }
}
