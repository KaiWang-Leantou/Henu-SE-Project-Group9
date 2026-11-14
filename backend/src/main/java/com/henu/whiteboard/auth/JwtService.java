package com.henu.whiteboard.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.henu.whiteboard.persistence.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验：HS256、12 小时有效，claims 为 sub/guest/name/iat/exp。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** 仅本地开发使用的兜底密钥；生产必须以 WB_JWT_SECRET 覆盖。 */
    private static final String DEV_SECRET = "wb-dev-only-jwt-secret-change-me-0123456789abcdef";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${wb.jwt.secret:}") String configuredSecret,
                      @Value("${wb.jwt.ttl-hours:12}") long ttlHours) {
        String secret = configuredSecret == null ? "" : configuredSecret.trim();
        if (secret.isEmpty()) {
            log.warn("WB_JWT_SECRET 未配置，使用 dev 默认 JWT 密钥；生产环境务必通过环境变量注入");
            secret = DEV_SECRET;
        }
        this.key = Keys.hmacShaKeyFor(deriveKey(secret));
        this.ttl = Duration.ofHours(ttlHours <= 0 ? 12 : ttlHours);
    }

    public String issue(UserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim("guest", user.isGuest())
                .claim("name", user.getNickname())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验并解析 token；过期抛 ExpiredJwtException，其余非法 token 抛 JwtException。
     */
    public JwtPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new JwtException("token 缺少 sub");
        }
        Boolean guest = claims.get("guest", Boolean.class);
        return new JwtPayload(userId, claims.get("name", String.class), Boolean.TRUE.equals(guest));
    }

    /** HS256 要求 ≥256 位密钥：短密钥用 SHA-256 派生，避免课程环境配置过短直接启动失败。 */
    private static byte[] deriveKey(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return raw;
        }
        log.warn("WB_JWT_SECRET 长度不足 32 字节，已用 SHA-256 派生密钥；建议直接配置 32 字节以上随机串");
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
