package io.github.devcircle.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Repository
public class AuthTokenRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuthTokenRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void saveRefreshToken(Long userId, String refreshToken, Instant expiresAt) {
        try {
            jdbcClient.sql("""
                            INSERT INTO refresh_token(user_id, token_hash, revoked, expires_at)
                            VALUES (:userId, :tokenHash, false, :expiresAt)
                            """)
                    .param("userId", userId)
                    .param("tokenHash", hash(refreshToken))
                    .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                    .update();
        } catch (DataAccessException ignored) {
        }
    }

    public boolean refreshTokenActive(String refreshToken) {
        try {
            Integer count = jdbcClient.sql("""
                            SELECT COUNT(*) FROM refresh_token
                            WHERE token_hash = :tokenHash AND revoked = false AND expires_at > CURRENT_TIMESTAMP
                            """)
                    .param("tokenHash", hash(refreshToken))
                    .query(Integer.class)
                    .single();
            return count != null && count > 0;
        } catch (DataAccessException ex) {
            return true;
        }
    }

    public void revokeRefreshToken(String refreshToken) {
        try {
            jdbcClient.sql("UPDATE refresh_token SET revoked = true WHERE token_hash = :tokenHash")
                    .param("tokenHash", hash(refreshToken))
                    .update();
        } catch (DataAccessException ignored) {
        }
    }

    public void audit(Long userId, String action, String targetType, String targetId, String traceId, Map<String, Object> metadata) {
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, trace_id, metadata)
                            VALUES (:userId, :action, :targetType, :targetId, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("userId", userId)
                    .param("action", action)
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata))
                    .update();
        } catch (DataAccessException | JsonProcessingException ignored) {
        }
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
