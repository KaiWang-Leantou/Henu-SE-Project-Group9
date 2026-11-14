package com.henu.whiteboard.auth;

import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;
import com.henu.whiteboard.persistence.UserEntity;
import com.henu.whiteboard.persistence.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static com.henu.whiteboard.auth.AuthDtos.GuestRequest;
import static com.henu.whiteboard.auth.AuthDtos.LoginRequest;
import static com.henu.whiteboard.auth.AuthDtos.RegisterRequest;
import static com.henu.whiteboard.auth.AuthDtos.TokenResponse;
import static com.henu.whiteboard.auth.AuthDtos.UpgradeRequest;
import static com.henu.whiteboard.auth.AuthDtos.UserView;

/**
 * 注册/登录/游客/升级/当前用户业务：校验规则与错误码严格按 docs/05 §1。
 */
@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{4,32}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    /** BCrypt 只取前 72 字节，超长直接拒绝，避免“密码后半段被静默忽略”。 */
    private static final int PASSWORD_MAX_LENGTH = 72;
    private static final int NICKNAME_MAX_LENGTH = 20;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (request == null) {
            throw validation("请求体不能为空");
        }
        String username = requireUsername(request.username());
        String password = requirePassword(request.password());
        String nickname = nicknameOrDefault(request.nickname(), username);
        if (userRepository.existsByUsername(username)) {
            throw usernameTaken();
        }
        UserEntity user = new UserEntity();
        user.setId(newUserId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setGuest(false);
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setLastLoginAt(Instant.now());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 并发注册同一用户名时由唯一约束兜底
            throw usernameTaken();
        }
        return tokenResponse(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw invalidCredentials();
        }
        UserEntity user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(AuthService::invalidCredentials);
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new ApiException(ApiCode.FORBIDDEN, "账号已被禁用");
        }
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return tokenResponse(user);
    }

    @Transactional
    public TokenResponse guest(GuestRequest request) {
        String nickname = request == null ? null : request.nickname();
        if (nickname != null && !nickname.isBlank()) {
            nickname = requireNickname(nickname);
        } else {
            nickname = "游客" + ThreadLocalRandom.current().nextInt(1000, 10000);
        }
        UserEntity user = new UserEntity();
        user.setId(newUserId());
        user.setNickname(nickname);
        user.setGuest(true);
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        return tokenResponse(user);
    }

    /**
     * 游客升级：同一条 wb_user 记录补 username/password_hash，id 不变，此前参与数据全部保留。
     */
    @Transactional
    public TokenResponse upgrade(CurrentUser current, UpgradeRequest request) {
        if (current == null) {
            throw new ApiException(ApiCode.AUTH_REQUIRED, "请先登录");
        }
        UserEntity user = userRepository.findById(current.id())
                .orElseThrow(() -> new ApiException(ApiCode.AUTH_REQUIRED, "账号不存在，请重新登录"));
        if (!user.isGuest()) {
            throw new ApiException(ApiCode.FORBIDDEN, "当前账号已是正式用户，无需升级");
        }
        if (request == null) {
            throw validation("请求体不能为空");
        }
        String username = requireUsername(request.username());
        String password = requirePassword(request.password());
        if (request.nickname() != null && !request.nickname().isBlank()) {
            user.setNickname(requireNickname(request.nickname()));
        }
        if (userRepository.existsByUsername(username)) {
            throw usernameTaken();
        }
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setGuest(false);
        user.setStatus(STATUS_ACTIVE);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken();
        }
        return tokenResponse(user);
    }

    @Transactional(readOnly = true)
    public UserView me(CurrentUser current) {
        if (current == null) {
            throw new ApiException(ApiCode.AUTH_REQUIRED, "请先登录");
        }
        UserEntity user = userRepository.findById(current.id())
                .orElseThrow(() -> new ApiException(ApiCode.AUTH_REQUIRED, "账号不存在，请重新登录"));
        return view(user);
    }

    private TokenResponse tokenResponse(UserEntity user) {
        return new TokenResponse(jwtService.issue(user), view(user));
    }

    private static UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getUsername(), user.getNickname(), user.isGuest());
    }

    private static String requireUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw validation("用户名需为 4–32 位字母、数字或下划线");
        }
        return username;
    }

    private static String requirePassword(String raw) {
        if (raw == null
                || raw.length() < PASSWORD_MIN_LENGTH
                || raw.length() > PASSWORD_MAX_LENGTH
                || !containsAsciiLetter(raw)
                || !containsAsciiDigit(raw)) {
            throw validation("密码至少 8 位，且同时包含字母和数字");
        }
        return raw;
    }

    private static String requireNickname(String raw) {
        String nickname = raw.trim();
        if (nickname.isEmpty() || nickname.length() > NICKNAME_MAX_LENGTH) {
            throw validation("昵称长度需为 1–20 个字符");
        }
        return nickname;
    }

    private static String nicknameOrDefault(String raw, String defaultName) {
        if (raw == null || raw.isBlank()) {
            return defaultName;
        }
        return requireNickname(raw);
    }

    private static boolean containsAsciiLetter(String value) {
        return value.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
    }

    private static boolean containsAsciiDigit(String value) {
        return value.chars().anyMatch(c -> c >= '0' && c <= '9');
    }

    private static String newUserId() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static ApiException validation(String message) {
        return new ApiException(ApiCode.VALIDATION_ERROR, message);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ApiCode.INVALID_CREDENTIALS, "用户名或密码错误");
    }

    private static ApiException usernameTaken() {
        return new ApiException(ApiCode.USERNAME_TAKEN, "用户名已被占用");
    }
}
