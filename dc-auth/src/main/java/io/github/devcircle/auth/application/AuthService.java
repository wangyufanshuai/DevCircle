package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ACCESS_SECONDS = 900;
    private static final int REFRESH_DAYS = 14;
    private final AuthTokenRepository repository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthTokenRepository repository, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    public SessionTokens register(String email, String password, String displayName, String traceId) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        String normalizedName = normalizeDisplayName(displayName);
        AuthTokenRepository.Account account = repository.createAccount(normalizedEmail, normalizedName, passwordEncoder.encode(password));
        repository.audit(account.id(), "REGISTER", "USER", String.valueOf(account.id()), traceId, Map.of());
        return newSession(account, UUID.randomUUID().toString());
    }

    public SessionTokens login(String email, String password, String traceId) {
        String normalizedEmail = normalizeEmail(email);
        AuthTokenRepository.Account account = repository.findAccountByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确"));
        if (!"ACTIVE".equals(account.status()) || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确");
        }
        repository.audit(account.id(), "LOGIN", "USER", String.valueOf(account.id()), traceId, Map.of());
        return newSession(account, UUID.randomUUID().toString());
    }

    public SessionTokens refresh(String refreshToken, String csrfToken, String traceId) {
        requireCookieTokens(refreshToken, csrfToken);
        String replacement = randomToken();
        String replacementCsrf = randomToken();
        Instant refreshExpiresAt = Instant.now().plus(REFRESH_DAYS, ChronoUnit.DAYS);
        AuthTokenRepository.Account account = repository.rotateRefreshToken(
                refreshToken, csrfToken, replacement, replacementCsrf, refreshExpiresAt);
        repository.audit(account.id(), "REFRESH_TOKEN", "USER", String.valueOf(account.id()), traceId, Map.of());
        return accessFor(account, replacement, replacementCsrf, refreshExpiresAt);
    }

    public SessionTokens oauthLogin(AuthTokenRepository.Account account, String traceId) {
        if (!"ACTIVE".equals(account.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号当前不可用");
        }
        repository.audit(account.id(), "OAUTH2_LOGIN", "USER", String.valueOf(account.id()), traceId, Map.of("provider", "github"));
        return newSession(account, UUID.randomUUID().toString());
    }

    public void logout(String refreshToken, String csrfToken, String traceId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        requireCookieTokens(refreshToken, csrfToken);
        repository.revokeRefreshToken(refreshToken, csrfToken);
        repository.audit(null, "LOGOUT", "SESSION", null, traceId, Map.of());
    }

    public UserSession parseAccessToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少访问令牌");
        }
        try {
            var claims = jwtService.parseAccess(authorization.substring("Bearer ".length()));
            String roles = claims.get("roles", String.class);
            return new UserSession(
                    Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class),
                    roles == null || roles.isBlank() ? List.of("USER") : List.of(roles.split(",")));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "访问令牌无效或已过期");
        }
    }

    private SessionTokens newSession(AuthTokenRepository.Account account, String familyId) {
        String refreshToken = randomToken();
        String csrfToken = randomToken();
        Instant refreshExpiresAt = Instant.now().plus(REFRESH_DAYS, ChronoUnit.DAYS);
        repository.saveRefreshToken(account.id(), refreshToken, csrfToken, familyId, refreshExpiresAt);
        return accessFor(account, refreshToken, csrfToken, refreshExpiresAt);
    }

    private SessionTokens accessFor(AuthTokenRepository.Account account,
                                    String refreshToken,
                                    String csrfToken,
                                    Instant refreshExpiresAt) {
        Instant accessExpiresAt = Instant.now().plusSeconds(ACCESS_SECONDS);
        String accessToken = jwtService.issueAccess(account.id(), account.displayName(), account.roles(), accessExpiresAt);
        return new SessionTokens(accessToken, accessExpiresAt, refreshToken, csrfToken, refreshExpiresAt,
                new UserSession(account.id(), account.displayName(), account.roles()));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "邮箱不能为空");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.length() < 2 || value.length() > 32) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "昵称长度应为 2 到 32 个字符");
        }
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码长度应为 12 到 128 个字符");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密码必须同时包含字母和数字");
        }
    }

    private void requireCookieTokens(String refreshToken, String csrfToken) {
        if (refreshToken == null || refreshToken.isBlank() || csrfToken == null || csrfToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_REVOKED, "登录状态已失效，请重新登录");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record SessionTokens(String accessToken,
                                Instant accessExpiresAt,
                                String refreshToken,
                                String csrfToken,
                                Instant refreshExpiresAt,
                                UserSession user) {
    }

    public record UserSession(Long userId, String displayName, List<String> roles) {
    }
}
