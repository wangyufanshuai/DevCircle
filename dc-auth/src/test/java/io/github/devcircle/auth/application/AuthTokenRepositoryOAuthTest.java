package io.github.devcircle.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTokenRepositoryOAuthTest {
    private JdbcClient jdbcClient;
    private AuthTokenRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:oauth-binding;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
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
                CREATE TABLE user_oauth_binding (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    provider_user_id VARCHAR(128) NOT NULL,
                    UNIQUE(provider, provider_user_id)
                )
                """).update();
        jdbcClient.sql("INSERT INTO role(code, name) VALUES ('USER', 'User')").update();
        repository = new AuthTokenRepository(jdbcClient, new ObjectMapper());
    }

    @Test
    void existingPasswordAccountRequiresExplicitAuthenticatedLinking() {
        jdbcClient.sql("""
                        INSERT INTO user_account(username, display_name, email, password_hash, status)
                        VALUES ('reader@example.com', 'Reader', 'reader@example.com', 'argon2-hash', 'ACTIVE')
                        """)
                .update();

        assertThatThrownBy(() -> repository.bindOrCreateOAuthAccount(
                "github", "github-42", "reader@example.com", "Reader", "unusable"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.OAUTH_ACCOUNT_CONFLICT));

        Integer bindings = jdbcClient.sql("SELECT COUNT(*) FROM user_oauth_binding").query(Integer.class).single();
        assertThat(bindings).isZero();
    }

    @Test
    void newVerifiedIdentityStillCreatesAndBindsAnAccount() {
        AuthTokenRepository.Account account = repository.bindOrCreateOAuthAccount(
                "github", "github-73", "new-reader@example.com", "New Reader", "unusable");

        assertThat(account.email()).isEqualTo("new-reader@example.com");
        Integer bindings = jdbcClient.sql("SELECT COUNT(*) FROM user_oauth_binding WHERE user_id = :userId")
                .param("userId", account.id())
                .query(Integer.class)
                .single();
        assertThat(bindings).isEqualTo(1);
    }
}
