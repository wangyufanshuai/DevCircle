package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceBoundaryTest {
    private AuthTokenRepository repository;
    private PasswordEncoder passwordEncoder;
    private AuthService service;
    private AuthTokenRepository.Account active;

    @BeforeEach
    void setUp() {
        repository = mock(AuthTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AuthService(repository, new JwtService(
                "boundary-test-secret-that-is-long-enough-for-jwt-signing-123456"), passwordEncoder);
        active = new AuthTokenRepository.Account(
                7L, "reader@example.com", "Reader", "password-hash", "ACTIVE", List.of("USER"));
    }

    @Test
    void registrationNormalizesIdentityAndRejectsInvalidCredentialsBeforeWriting() {
        when(passwordEncoder.encode("valid-password1")).thenReturn("encoded-password");
        when(repository.createAccount("reader@example.com", "Reader", "encoded-password")).thenReturn(active);

        AuthService.SessionTokens tokens = service.register(
                "  READER@example.com ", "valid-password1", "  Reader  ", "trace-register");

        assertThat(tokens.user().userId()).isEqualTo(7L);
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.csrfToken()).isNotBlank();
        verify(repository).createAccount("reader@example.com", "Reader", "encoded-password");
        verify(repository).saveRefreshToken(eq(7L), anyString(), anyString(), anyString(), any(Instant.class));

        assertCode(() -> service.register(null, "valid-password1", "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(" ", "valid-password1", "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", null, "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "short1", "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "x".repeat(129), "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "letters-only-password", "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "123456789012", "Reader", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "valid-password1", "x", "trace"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register("reader@example.com", "valid-password1", "x".repeat(33), "trace"), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void loginRejectsMissingDisabledAndWrongPasswordAccounts() {
        when(repository.findAccountByEmail("missing@example.com")).thenReturn(Optional.empty());
        assertCode(() -> service.login("missing@example.com", "valid-password1", "trace"),
                ErrorCode.AUTHENTICATION_FAILED);

        var disabled = new AuthTokenRepository.Account(
                8L, "disabled@example.com", "Disabled", "password-hash", "BANNED", List.of("USER"));
        when(repository.findAccountByEmail("disabled@example.com")).thenReturn(Optional.of(disabled));
        assertCode(() -> service.login("disabled@example.com", "valid-password1", "trace"),
                ErrorCode.AUTHENTICATION_FAILED);

        when(repository.findAccountByEmail("reader@example.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("wrong-password1", "password-hash")).thenReturn(false);
        assertCode(() -> service.login("reader@example.com", "wrong-password1", "trace"),
                ErrorCode.AUTHENTICATION_FAILED);
        verify(repository, never()).revokeUserSessions(8L);
    }

    @Test
    void compatibilityLoginAndRefreshIssueRealSessionsAndAuditThem() {
        when(repository.findAccountByEmail("reader@example.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("valid-password1", "password-hash")).thenReturn(true);

        AuthService.SessionTokens login = service.login(
                "reader@example.com", "valid-password1", "trace-login");
        when(repository.rotateRefreshToken(eq(login.refreshToken()), eq(login.csrfToken()),
                anyString(), anyString(), any(Instant.class))).thenReturn(active);
        AuthService.SessionTokens refreshed = service.refresh(
                login.refreshToken(), login.csrfToken(), "trace-refresh");

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.csrfToken()).isNotEqualTo(login.csrfToken());
        assertThat(service.parseAccessToken("Bearer " + refreshed.accessToken()).userId()).isEqualTo(7L);
        verify(repository).audit(7L, "LOGIN", "USER", "7", "trace-login", java.util.Map.of());
        verify(repository).audit(7L, "REFRESH_TOKEN", "USER", "7", "trace-refresh", java.util.Map.of());
    }

    @Test
    void refreshAndLogoutRequireTheDoubleSubmitCookiePair() {
        assertCode(() -> service.refresh(null, "csrf", "trace"), ErrorCode.TOKEN_REVOKED);
        assertCode(() -> service.refresh("refresh", null, "trace"), ErrorCode.TOKEN_REVOKED);
        assertCode(() -> service.refresh(" ", "csrf", "trace"), ErrorCode.TOKEN_REVOKED);
        assertCode(() -> service.refresh("refresh", " ", "trace"), ErrorCode.TOKEN_REVOKED);

        service.logout(null, null, "trace-empty");
        service.logout(" ", null, "trace-empty");
        assertCode(() -> service.logout("refresh", null, "trace"), ErrorCode.TOKEN_REVOKED);
        assertCode(() -> service.logout("refresh", " ", "trace"), ErrorCode.TOKEN_REVOKED);

        service.logout("refresh", "csrf", "trace-logout");
        verify(repository).revokeRefreshToken("refresh", "csrf");
        verify(repository).audit(null, "LOGOUT", "SESSION", null, "trace-logout", java.util.Map.of());
    }

    @Test
    void oauthAndBearerParsingRejectInactiveOrMalformedSessions() {
        var admin = new AuthTokenRepository.Account(
                9L, "admin@example.com", "Admin", "password-hash", "ACTIVE", List.of("USER", "ADMIN"));
        AuthService.SessionTokens tokens = service.oauthLogin(admin, "trace-oauth");
        AuthService.UserSession parsed = service.parseAccessToken("Bearer " + tokens.accessToken());
        assertThat(parsed.roles()).containsExactly("USER", "ADMIN");

        var noRoles = new AuthTokenRepository.Account(
                10L, "plain@example.com", "Plain", "password-hash", "ACTIVE", List.of());
        AuthService.UserSession defaultRole = service.parseAccessToken(
                "Bearer " + service.oauthLogin(noRoles, "trace-plain").accessToken());
        assertThat(defaultRole.roles()).containsExactly("USER");

        var banned = new AuthTokenRepository.Account(
                11L, "banned@example.com", "Banned", "password-hash", "BANNED", List.of("USER"));
        assertCode(() -> service.oauthLogin(banned, "trace-banned"), ErrorCode.FORBIDDEN);
        assertCode(() -> service.parseAccessToken(null), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.parseAccessToken("Basic credential"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.parseAccessToken("Bearer not-a-jwt"), ErrorCode.UNAUTHORIZED);
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
