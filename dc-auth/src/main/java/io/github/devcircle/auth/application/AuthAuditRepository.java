package io.github.devcircle.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

/** Owns authentication audit-log persistence and metadata serialization. */
final class AuthAuditRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuthAuditRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    void write(Long userId, String action, String targetType, String targetId,
               String traceId, Map<String, Object> metadata) {
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, trace_id, metadata)
                            VALUES (:userId, :action, :targetType, :targetId, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("userId", userId).param("action", action).param("targetType", targetType)
                    .param("targetId", targetId).param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit metadata", ex);
        }
    }
}
