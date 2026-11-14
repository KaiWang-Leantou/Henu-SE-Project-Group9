package com.henu.whiteboard.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiResponse;
import com.henu.whiteboard.persistence.UserEntity;
import com.henu.whiteboard.persistence.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * /api/** 的 Bearer 鉴权过滤器：解析 JWT、回查用户后写入 UserContext，finally 清理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwtService,
                      UserRepository userRepository,
                      ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return "/api/auth/register".equals(path)
                || "/api/auth/login".equals(path)
                || "/api/auth/guest".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith(BEARER_PREFIX)) {
                reject(response, ApiCode.AUTH_REQUIRED, "请先登录");
                return;
            }
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (token.isEmpty()) {
                reject(response, ApiCode.AUTH_REQUIRED, "请先登录");
                return;
            }

            final JwtPayload payload;
            try {
                payload = jwtService.parse(token);
            } catch (ExpiredJwtException e) {
                reject(response, ApiCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
                return;
            } catch (JwtException | IllegalArgumentException e) {
                reject(response, ApiCode.AUTH_REQUIRED, "登录状态无效，请重新登录");
                return;
            }

            UserEntity user = userRepository.findById(payload.userId()).orElse(null);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                reject(response, ApiCode.AUTH_REQUIRED, "账号不存在或已被禁用");
                return;
            }
            UserContext.set(new CurrentUser(user.getId(), user.getUsername(),
                    user.getNickname(), user.isGuest()));
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private void reject(HttpServletResponse response, ApiCode code, String message) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
