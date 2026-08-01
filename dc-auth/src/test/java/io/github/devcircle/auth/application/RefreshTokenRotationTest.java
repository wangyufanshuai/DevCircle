package io.github.devcircle.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(RefreshTokenRotationTest.TestConfig.class)
class RefreshTokenRotationTest {
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AuthTokenRepository repository;
    @Autowired
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE user_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL UNIQUE,
                    display_name VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL UNIQUE,
                    password_hash VARCHAR(255) NOT NULL,
                    status VARCHAR(32) NOT NULL
                )
                """).update();
        jdbcClient.sql("CREATE TABLE role (id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(64) UNIQUE, name VARCHAR(64))").update();
        jdbcClient.sql("CREATE TABLE user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY(user_id, role_id))").update();
        jdbcClient.sql("""
                CREATE TABLE refresh_token (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    token_hash CHAR(64) NOT NULL UNIQUE,
                    csrf_hash CHAR(64) NOT NULL,
                    family_id CHAR(36) NOT NULL,
                    revoked BOOLEAN NOT NULL DEFAULT FALSE,
                    used_at TIMESTAMP,
                    replaced_by_hash CHAR(64),
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();
        jdbcClient.sql("""
                CREATE TABLE auth_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    operation VARCHAR(32) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
                    user_id BIGINT,
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT,
                    action VARCHAR(96) NOT NULL,
                    target_type VARCHAR(64) NOT NULL,
                    target_id VARCHAR(96),
                    trace_id VARCHAR(128),
                    metadata JSON,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("INSERT INTO role(code, name) VALUES ('USER', 'User')").update();
        jdbcClient.sql("""
                        INSERT INTO user_account(id, username, display_name, email, password_hash, status)
                        VALUES (7, 'reader@example.com', 'Reader', 'reader@example.com', 'argon2-hash', 'ACTIVE')
                        """).update();
        jdbcClient.sql("INSERT INTO user_role(user_id, role_id) SELECT 7, id FROM role WHERE code = 'USER'").update();
    }

    @Test
    void replayingARotatedTokenRevokesTheReplacementFamily() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "refresh-old", "csrf-old", "family-1", expiresAt);
        repository.rotateRefreshToken("refresh-old", "csrf-old", "refresh-new", "csrf-new", expiresAt);

        assertThat(revocationStates("family-1")).containsExactly(true, false);

        assertThatThrownBy(() -> repository.rotateRefreshToken(
                "refresh-old", "csrf-old", "refresh-attacker", "csrf-attacker", expiresAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));

        assertThat(revocationStates("family-1")).containsExactly(true, true);
    }

    @Test
    void csrfMismatchRevokesTheWholeFamilyEvenThoughTheRequestFails() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "refresh-one", "csrf-one", "family-2", expiresAt);
        repository.saveRefreshToken(7L, "refresh-two", "csrf-two", "family-2", expiresAt);

        assertThatThrownBy(() -> repository.rotateRefreshToken(
                "refresh-one", "wrong-csrf", "replacement", "replacement-csrf", expiresAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));

        assertThat(revocationStates("family-2")).containsExactly(true, true);
    }

    @Test
    void idempotentRefreshCannotRollBackFamilyRevocationOnCsrfFailure() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "refresh-idempotent", "csrf-idempotent", "family-3", expiresAt);
        repository.saveRefreshToken(7L, "refresh-sibling", "csrf-sibling", "family-3", expiresAt);

        assertThatThrownBy(() -> authService.refresh(
                "refresh-idempotent", "wrong-csrf", "refresh-request-key", "trace"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));

        assertThat(revocationStates("family-3")).containsExactly(true, true);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM auth_idempotency_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void idempotentLogoutCannotRollBackFamilyRevocationOnCsrfFailure() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "logout-primary", "logout-csrf", "family-4", expiresAt);
        repository.saveRefreshToken(7L, "logout-sibling", "sibling-csrf", "family-4", expiresAt);

        assertThatThrownBy(() -> authService.logout(
                "logout-primary", "wrong-csrf", "logout-request-key", "trace"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));

        assertThat(revocationStates("family-4")).containsExactly(true, true);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM auth_idempotency_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void ordinaryAuthenticationFailureStillRollsBackItsIdempotencyClaim() {
        assertThatThrownBy(() -> authService.login(
                "reader@example.com", "wrong-password", "failed-login-key", "trace"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM auth_idempotency_record")
                .query(Long.class).single()).isZero();
    }

    @Test
    void disabledAccountCannotRotateARefreshTokenAndLosesTheWholeFamily() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "disabled-primary", "disabled-csrf", "family-5", expiresAt);
        repository.saveRefreshToken(7L, "disabled-sibling", "sibling-csrf", "family-5", expiresAt);
        jdbcClient.sql("UPDATE user_account SET status = 'BANNED' WHERE id = 7").update();

        assertThatThrownBy(() -> repository.rotateRefreshToken(
                "disabled-primary", "disabled-csrf", "must-not-exist", "replacement-csrf", expiresAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));

        assertThat(revocationStates("family-5")).containsExactly(true, true);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM refresh_token WHERE family_id = 'family-5'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void completedRefreshCannotBeReplayedAfterItsReplacementWasLoggedOut() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        repository.saveRefreshToken(7L, "replay-source", "replay-csrf", "family-6", expiresAt);

        AuthService.SessionTokens replacement = authService.refresh(
                "replay-source", "replay-csrf", "refresh-replay-key", "trace-refresh");
        authService.logout(replacement.refreshToken(), replacement.csrfToken(),
                "logout-replacement-key", "trace-logout");

        assertThatThrownBy(() -> authService.refresh(
                "replay-source", "replay-csrf", "refresh-replay-key", "trace-refresh"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TOKEN_REVOKED));
        assertThat(revocationStates("family-6")).containsExactly(true, true);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'REFRESH_TOKEN'")
                .query(Long.class).single()).isOne();
    }

    private List<Boolean> revocationStates(String familyId) {
        return jdbcClient.sql("SELECT revoked FROM refresh_token WHERE family_id = :familyId ORDER BY id")
                .param("familyId", familyId)
                .query(Boolean.class)
                .list();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:refresh-rotation;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AuthTokenRepository authTokenRepository(JdbcClient jdbcClient) {
            return new AuthTokenRepository(jdbcClient, new ObjectMapper());
        }

        @Bean
        AuthIdempotencyService authIdempotencyService(JdbcClient jdbcClient) {
            return new AuthIdempotencyService(jdbcClient,
                    "test-signing-secret-that-is-long-enough-for-auth-idempotency-123456");
        }

        @Bean
        AuthService authService(AuthTokenRepository repository, AuthIdempotencyService idempotencyService) {
            PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
            return new AuthService(repository,
                    new JwtService("test-secret-that-is-long-enough-for-production-style-validation-123456"),
                    passwordEncoder, idempotencyService);
        }
    }
}
