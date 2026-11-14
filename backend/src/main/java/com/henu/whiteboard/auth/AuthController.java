package com.henu.whiteboard.auth;

import com.henu.whiteboard.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.henu.whiteboard.auth.AuthDtos.GuestRequest;
import static com.henu.whiteboard.auth.AuthDtos.LoginRequest;
import static com.henu.whiteboard.auth.AuthDtos.RegisterRequest;
import static com.henu.whiteboard.auth.AuthDtos.TokenResponse;
import static com.henu.whiteboard.auth.AuthDtos.UpgradeRequest;
import static com.henu.whiteboard.auth.AuthDtos.UserView;

/**
 * 认证 REST 入口：/api/auth/register|login|guest|upgrade|me|logout。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@RequestBody(required = false) RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody(required = false) LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/guest")
    public ApiResponse<TokenResponse> guest(@RequestBody(required = false) GuestRequest request) {
        return ApiResponse.ok(authService.guest(request));
    }

    @PostMapping("/upgrade")
    public ApiResponse<TokenResponse> upgrade(@RequestBody(required = false) UpgradeRequest request) {
        return ApiResponse.ok(authService.upgrade(UserContext.require(), request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.me(UserContext.require()));
    }

    /** V1 前端只需丢弃 token，服务端按 docs/04 返回 204。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
