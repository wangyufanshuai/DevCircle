package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AuthIdempotencyService idempotency;

    @Autowired
    public AuthService(AuthTokenRepository repository, JwtService jwtService, PasswordEncoder passwordEncoder,
                       AuthIdempotencyService idempotency) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.idempotency = idempotency;
    }

    /** Compatibility constructor for isolated unit tests that do not load the auth database. */
    public AuthService(AuthTokenRepository repository, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this(repository, jwtService, passwordEncoder, null);
    }

    public SessionTokens register(String email, String password, String displayName, String traceId) {
        return register(email, password, displayName, null, traceId);
    }

    public SessionTokens register(String email, String password, String displayName,
                                  String idempotencyKey, String traceId) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        String normalizedName = normalizeDisplayName(displayName);
        if (idempotency == null) {
            return registerInternal(normalizedEmail, password, normalizedName, traceId, null);
        }
        return idempotency.executeSession(idempotencyKey, "REGISTER",
                normalizedEmail + "\u0000" + password + "\u0000" + normalizedName,
                () -> registerInternal(normalizedEmail, password, normalizedName, traceId, idempotencyKey),
                () -> replaySession(normalizedEmail, idempotencyKey));
    }

    private SessionTokens registerInternal(String normalizedEmail, String password, String normalizedName,
                                           String traceId, String idempotencyKey) {
        AuthTokenRepository.Account account = repository.createAccount(normalizedEmail, normalizedName, passwordEncoder.encode(password));
        repository.audit(account.id(), "REGISTER", "USER", String.valueOf(account.id()), traceId, Map.of());
        return newSession(account, idempotencyKey);
    }

    public SessionTokens login(String email, String password, String traceId) {
        return login(email, password, null, traceId);
    }

    public SessionTokens login(String email, String password, String idempotencyKey, String traceId) {
        String normalizedEmail = normalizeEmail(email);
        if (idempotency == null) {
            return loginInternal(normalizedEmail, password, traceId, null);
        }
        return idempotency.executeSession(idempotencyKey, "LOGIN", normalizedEmail + "\u0000" + password,
                () -> loginInternal(normalizedEmail, password, traceId, idempotencyKey),
                () -> replaySession(normalizedEmail, idempotencyKey));
    }

    private SessionTokens loginInternal(String normalizedEmail, String password, String traceId, String idempotencyKey) {
        AuthTokenRepository.Account account = repository.findAccountByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确"));
        if (!"ACTIVE".equals(account.status()) || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确");
        }
        repository.audit(account.id(), "LOGIN", "USER", String.valueOf(account.id()), traceId, Map.of());
        return newSession(account, idempotencyKey);
    }

    public SessionTokens refresh(String refreshToken, String csrfToken, String traceId) {
        return refresh(refreshToken, csrfToken, null, traceId);
    }

    public SessionTokens refresh(String refreshToken, String csrfToken, String idempotencyKey, String traceId) {
        requireCookieTokens(refreshToken, csrfToken);
        if (idempotency == null) {
            return refreshInternal(refreshToken, csrfToken, traceId);
        }
        return idempotency.executeSession(idempotencyKey, "REFRESH",
                refreshToken + "\u0000" + csrfToken,
                () -> refreshInternal(refreshToken, csrfToken, traceId, idempotencyKey),
                () -> replayRefresh(refreshToken, csrfToken, idempotencyKey));
    }

    private SessionTokens refreshInternal(String refreshToken, String csrfToken, String traceId) {
        return refreshInternal(refreshToken, csrfToken, traceId, null);
    }

    private SessionTokens refreshInternal(String refreshToken, String csrfToken, String traceId, String idempotencyKey) {
        String replacement = idempotencyKey == null ? randomToken() : idempotency.deriveOpaqueToken("refresh", idempotencyKey + "\u0000" + refreshToken);
        String replacementCsrf = idempotencyKey == null ? randomToken() : idempotency.deriveOpaqueToken("csrf", idempotencyKey + "\u0000" + refreshToken);
        Instant refreshExpiresAt = Instant.now().plus(REFRESH_DAYS, ChronoUnit.DAYS);
        AuthTokenRepository.Account account = repository.rotateRefreshToken(
                refreshToken, csrfToken, replacement, replacementCsrf, refreshExpiresAt);
        repository.audit(account.id(), "REFRESH_TOKEN", "USER", String.valueOf(account.id()), traceId, Map.of());
        return accessFor(account, replacement, replacementCsrf, refreshExpiresAt);
    }

    private SessionTokens replaySession(String normalizedEmail, String idempotencyKey) {
        AuthTokenRepository.Account account = repository.findAccountByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "账号已不可用，请重新登录"));
        return newSession(account, idempotencyKey);
    }

    private SessionTokens replayRefresh(String refreshToken, String csrfToken, String idempotencyKey) {
        String replacement = idempotency.deriveOpaqueToken("refresh", idempotencyKey + "\u0000" + refreshToken);
        String replacementCsrf = idempotency.deriveOpaqueToken("csrf", idempotencyKey + "\u0000" + refreshToken);
        AuthTokenRepository.RefreshTokenView view = repository.findActiveRefreshToken(replacement)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_REVOKED, "刷新请求已失效，请重新登录"));
        AuthTokenRepository.Account account = repository.findAccountById(view.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
        return accessFor(account, replacement, replacementCsrf, view.expiresAt());
    }

    public SessionTokens oauthLogin(AuthTokenRepository.Account account, String traceId) {
        if (!"ACTIVE".equals(account.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号当前不可用");
        }
        repository.audit(account.id(), "OAUTH2_LOGIN", "USER", String.valueOf(account.id()), traceId, Map.of("provider", "github"));
        return newSession(account, null);
    }

    public void logout(String refreshToken, String csrfToken, String traceId) {
        logout(refreshToken, csrfToken, null, traceId);
    }

    public void logout(String refreshToken, String csrfToken, String idempotencyKey, String traceId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        requireCookieTokens(refreshToken, csrfToken);
        if (idempotency == null) {
            logoutInternal(refreshToken, csrfToken, traceId);
            return;
        }
        idempotency.executeVoid(idempotencyKey, "LOGOUT", refreshToken + "\u0000" + csrfToken,
                () -> logoutInternal(refreshToken, csrfToken, traceId));
    }

    private void logoutInternal(String refreshToken, String csrfToken, String traceId) {
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

    private SessionTokens newSession(AuthTokenRepository.Account account, String idempotencyKey) {
        requireActiveSessionAccount(account);
        boolean deterministic = idempotencyKey != null;
        String refreshToken = deterministic ? idempotency.deriveOpaqueToken("refresh", idempotencyKey) : randomToken();
        String csrfToken = deterministic ? idempotency.deriveOpaqueToken("csrf", idempotencyKey) : randomToken();
        String familyId = deterministic
                ? UUID.nameUUIDFromBytes(("family\u0000" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
                : UUID.randomUUID().toString();
        Instant refreshExpiresAt = Instant.now().plus(REFRESH_DAYS, ChronoUnit.DAYS);
        if (deterministic) {
            repository.saveRefreshTokenIfAbsent(account.id(), refreshToken, csrfToken, familyId, refreshExpiresAt);
        } else {
            repository.saveRefreshToken(account.id(), refreshToken, csrfToken, familyId, refreshExpiresAt);
        }
        return accessFor(account, refreshToken, csrfToken, refreshExpiresAt);
    }

    private SessionTokens accessFor(AuthTokenRepository.Account account,
                                    String refreshToken,
                                    String csrfToken,
                                    Instant refreshExpiresAt) {
        requireActiveSessionAccount(account);
        Instant accessExpiresAt = Instant.now().plusSeconds(ACCESS_SECONDS);
        String accessToken = jwtService.issueAccess(account.id(), account.displayName(), account.roles(), accessExpiresAt);
        return new SessionTokens(accessToken, accessExpiresAt, refreshToken, csrfToken, refreshExpiresAt,
                new UserSession(account.id(), account.displayName(), account.roles()));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "邮箱不能为空");
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "邮箱长度不能超过 128 个字符");
        }
        return value;
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

    private void requireActiveSessionAccount(AuthTokenRepository.Account account) {
        if (!"ACTIVE".equals(account.status())) {
            repository.revokeUserSessions(account.id());
            throw new TokenRevokedException("账号当前不可用，请重新登录");
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
