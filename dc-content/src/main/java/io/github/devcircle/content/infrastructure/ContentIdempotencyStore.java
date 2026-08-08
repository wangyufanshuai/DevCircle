package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContentIdempotencyStore {
    private final JdbcClient jdbcClient;

    public ContentIdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public Long claim(Long userId, String operation, String idempotencyKey, String requestFingerprint) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO content_idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES (:key, :userId, :operation, :requestFingerprint)
                        """)
                .param("key", idempotencyKey)
                .param("userId", userId)
                .param("operation", operation)
                .param("requestFingerprint", requestFingerprint)
                .update();
        if (inserted == 1) return null;
        IdempotencyRecord existing = jdbcClient.sql("""
                        SELECT user_id, operation, request_fingerprint, resource_id
                        FROM content_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("user_id"), rs.getString("operation"), rs.getString("request_fingerprint"),
                        (Long) rs.getObject("resource_id")))
                .single();
        boolean legacyRecord = IdempotencyFingerprint.LEGACY.equals(existing.requestFingerprint());
        if (!existing.userId().equals(userId) || !existing.operation().equals(operation)
                || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                    "Idempotency-Key 已被其他写操作或请求载荷使用");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同请求正在处理中");
        }
        return existing.resourceId();
    }

    public void attachResource(String idempotencyKey, Long resourceId) {
        jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                .param("resourceId", resourceId)
                .param("key", idempotencyKey)
                .update();
    }

    private record IdempotencyRecord(Long userId, String operation, String requestFingerprint, Long resourceId) {
    }
}
