package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthIdempotencyServiceTest {
    private AuthIdempotencyService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth-idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1");
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
        service = new AuthIdempotencyService(jdbcClient,
                "test-signing-secret-that-is-long-enough-for-auth-idempotency-123456");
    }

    @Test
    void repeatedSessionRequestReplaysWithoutRunningTheMutationAgain() {
        AtomicInteger executions = new AtomicInteger();
        AuthService.SessionTokens original = tokens("refresh-1");

        AuthService.SessionTokens first = service.executeSession("auth-key-1", "LOGIN", "reader\u0000password", () -> {
            executions.incrementAndGet();
            return original;
        }, () -> original);
        AuthService.SessionTokens duplicate = service.executeSession("auth-key-1", "LOGIN", "reader\u0000password", () -> {
            executions.incrementAndGet();
            return tokens("refresh-should-not-be-created");
        }, () -> original);

        assertThat(first).isEqualTo(original);
        assertThat(duplicate).isEqualTo(original);
        assertThat(executions).hasValue(1);
    }

    @Test
    void reusingAKeyForDifferentAuthInputIsRejected() {
        service.executeSession("auth-key-2", "LOGIN", "reader\u0000password", () -> tokens("refresh-2"), () -> tokens("refresh-2"));

        assertThatThrownBy(() -> service.executeSession("auth-key-2", "LOGIN", "reader\u0000other",
                () -> tokens("refresh-3"), () -> tokens("refresh-3")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.DUPLICATE_OPERATION));
    }

    @Test
    void repeatedLogoutDoesNotRunTheRevocationActionAgain() {
        AtomicInteger executions = new AtomicInteger();
        service.executeVoid("auth-key-3", "LOGOUT", "refresh\u0000csrf", executions::incrementAndGet);
        service.executeVoid("auth-key-3", "LOGOUT", "refresh\u0000csrf", executions::incrementAndGet);
        assertThat(executions).hasValue(1);
    }

    private AuthService.SessionTokens tokens(String refreshToken) {
        Instant expiresAt = Instant.parse("2099-01-01T00:00:00Z");
        return new AuthService.SessionTokens("access-" + refreshToken, expiresAt, refreshToken, "csrf-" + refreshToken,
                expiresAt, new AuthService.UserSession(7L, "Reader", List.of("USER")));
    }
}
