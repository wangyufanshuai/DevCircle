package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

/** Owns one-time OAuth state and PKCE verifier persistence. */
final class AuthOAuthStateRepository {
    private final JdbcClient jdbcClient;
    private final AuthSecretHasher hasher;

    AuthOAuthStateRepository(JdbcClient jdbcClient, AuthSecretHasher hasher) {
        this.jdbcClient = jdbcClient;
        this.hasher = hasher;
    }

    void save(String rawState, String verifier, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO oauth_login_state(state_hash, verifier, expires_at, used)
                        VALUES (:stateHash, :verifier, :expiresAt, false)
                        """)
                .param("stateHash", hasher.hash(rawState)).param("verifier", verifier)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt)).update();
    }

    String consume(String rawState) {
        OAuthState state = jdbcClient.sql("""
                        SELECT id, verifier
                        FROM oauth_login_state
                        WHERE state_hash = :stateHash AND used = false AND expires_at > CURRENT_TIMESTAMP
                        FOR UPDATE
                        """)
                .param("stateHash", hasher.hash(rawState))
                .query((rs, rowNum) -> new OAuthState(rs.getLong("id"), rs.getString("verifier")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_STATE_INVALID, "OAuth 登录状态无效或已过期"));
        jdbcClient.sql("UPDATE oauth_login_state SET used = true, used_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", state.id()).update();
        return state.verifier();
    }

    private record OAuthState(Long id, String verifier) {
    }
}
