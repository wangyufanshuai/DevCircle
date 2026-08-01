package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceIdempotencyTest {
    private AuthTokenRepository repository;
    private AuthIdempotencyService idempotency;
    private AuthService service;
    private AuthTokenRepository.Account account;
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth-service-idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE auth_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    operation VARCHAR(32) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
                    user_id BIGINT,
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();
        repository = mock(AuthTokenRepository.class);
        passwordEncoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        idempotency = new AuthIdempotencyService(jdbcClient,
                "test-signing-secret-that-is-long-enough-for-auth-idempotency-123456");
        account = new AuthTokenRepository.Account(7L, "reader@example.com", "Reader", "argon2-hash", "ACTIVE", List.of("USER"));
        service = new AuthService(repository, new JwtService(
                "test-secret-that-is-long-enough-for-production-style-validation-123456"),
                passwordEncoder, idempotency);
    }

    @Test
    void loginRetryReusesTheDeterministicRefreshSessionWithoutCreatingAnotherAccountSession() {
        when(repository.findAccountByEmail("reader@example.com")).thenReturn(java.util.Optional.of(account));
        when(repository.findAccountById(7L)).thenReturn(java.util.Optional.of(account));
        doNothing().when(repository).saveRefreshTokenIfAbsent(any(), anyString(), anyString(), anyString(), any());
        when(passwordEncoder.matches("correct-password1", "argon2-hash")).thenReturn(true);

        AuthService.SessionTokens first = service.login("reader@example.com", "correct-password1", "login-key", "trace");
        AuthService.SessionTokens retry = service.login("reader@example.com", "correct-password1", "login-key", "trace");

        assertThat(retry.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(retry.csrfToken()).isEqualTo(first.csrfToken());
        verify(repository, times(2)).saveRefreshTokenIfAbsent(eq(7L), eq(first.refreshToken()), eq(first.csrfToken()), anyString(), any());
        verify(repository, times(2)).findAccountByEmail("reader@example.com");
    }

    @Test
    void completedLoginReplayCannotIssueASessionForADisabledAccount() {
        var disabled = new AuthTokenRepository.Account(7L, "reader@example.com", "Reader",
                "argon2-hash", "BANNED", List.of("USER"));
        when(repository.findAccountByEmail("reader@example.com"))
                .thenReturn(java.util.Optional.of(account), java.util.Optional.of(disabled));
        when(passwordEncoder.matches("correct-password1", "argon2-hash")).thenReturn(true);

        service.login("reader@example.com", "correct-password1", "disabled-replay-key", "trace");

        assertThatThrownBy(() -> service.login(
                "reader@example.com", "correct-password1", "disabled-replay-key", "trace"))
                .isInstanceOfSatisfying(io.github.devcircle.common.error.BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));
        verify(repository).revokeUserSessions(7L);
    }

    @Test
    void completedRefreshReplayCannotIssueASessionForADisabledAccount() {
        var disabled = new AuthTokenRepository.Account(7L, "reader@example.com", "Reader",
                "argon2-hash", "BANNED", List.of("USER"));
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(repository.rotateRefreshToken(eq("refresh-old"), eq("csrf-old"), anyString(), anyString(), any()))
                .thenReturn(account);
        when(repository.findActiveRefreshToken(anyString()))
                .thenReturn(java.util.Optional.of(new AuthTokenRepository.RefreshTokenView(7L, expiresAt)));
        when(repository.findAccountById(7L)).thenReturn(java.util.Optional.of(disabled));

        service.refresh("refresh-old", "csrf-old", "disabled-refresh-key", "trace");

        assertThatThrownBy(() -> service.refresh(
                "refresh-old", "csrf-old", "disabled-refresh-key", "trace"))
                .isInstanceOfSatisfying(io.github.devcircle.common.error.BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));
        verify(repository).revokeUserSessions(7L);
    }
}
