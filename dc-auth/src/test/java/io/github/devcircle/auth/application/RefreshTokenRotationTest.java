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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(RefreshTokenRotationTest.TestConfig.class)
class RefreshTokenRotationTest {
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AuthTokenRepository repository;

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
    }
}
