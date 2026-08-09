package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Shared transactional guardrails used by Admin write-side services. */
@Component
public class AdminMutationSupport {
    private static final String LEGACY_REQUEST_FINGERPRINT = "0".repeat(64);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public AdminMutationSupport(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void requireDatabase() {
        if (jdbcClient == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核数据服务暂时不可用");
        }
    }

    public BusinessException dependencyUnavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营数据暂时不可用");
    }

    public BusinessException dependencyUnavailable(DataAccessException cause) {
        BusinessException failure = dependencyUnavailable();
        failure.initCause(cause);
        return failure;
    }

    public void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核操作必须填写理由");
        }
        if (reason.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核理由不能超过 512 个字符");
        }
    }

    public void requireAuditor(Long auditorId) {
        if (auditorId == null || auditorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    public void requirePositiveId(Long value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    public AdminActionResult idempotent(Long actorId, String operation, String key, Long resourceId,
                                        String action, String requestFingerprint,
                                        Supplier<AdminActionResult> mutation) {
        return idempotent(actorId, operation, key, resourceId, action, requestFingerprint,
                Map.of("resourceId", resourceId), mutation);
    }

    public AdminActionResult idempotent(Long actorId, String operation, String key, Long resourceId,
                                        String action, String requestFingerprint,
                                        Map<String, Object> replayAttributes,
                                        Supplier<AdminActionResult> mutation) {
        try {
            Long duplicateId = claimIdempotency(actorId, operation, key, requestFingerprint);
            if (duplicateId != null) {
                Map<String, Object> attributes = new LinkedHashMap<>(replayAttributes);
                attributes.putIfAbsent("resourceId", duplicateId);
                attributes.put("duplicateRequest", true);
                return new AdminActionResult(action, Map.copyOf(attributes));
            }
            AdminActionResult result = mutation.get();
            jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                    .param("resourceId", resourceId)
                    .param("key", key)
                    .update();
            return result;
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    public Long claimIdempotency(Long actorId, String operation, String key, String requestFingerprint) {
        requireDatabase();
        requireAuditor(actorId);
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核写操作必须提供有效的 Idempotency-Key");
        }
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO content_idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES (:key, :actorId, :operation, :requestFingerprint)
                        """)
                .param("key", key)
                .param("actorId", actorId)
                .param("operation", operation)
                .param("requestFingerprint", requestFingerprint)
                .update();
        if (inserted == 1) return null;
        IdempotencyRecord existing = jdbcClient.sql("""
                        SELECT user_id, operation, request_fingerprint, resource_id
                        FROM content_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("user_id"), rs.getString("operation"), rs.getString("request_fingerprint"),
                        (Long) rs.getObject("resource_id")))
                .single();
        boolean legacyRecord = LEGACY_REQUEST_FINGERPRINT.equals(existing.requestFingerprint());
        if (!existing.actorId().equals(actorId) || !existing.operation().equals(operation)
                || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                    "Idempotency-Key 已被其他审核操作或请求载荷使用");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同审核请求正在处理中");
        }
        return existing.resourceId();
    }

    public String requestFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:|");
            } else {
                String text = String.valueOf(value);
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public void logAudit(Long userId, String action, String targetType, String targetId, String ip,
                         String traceId, Map<String, Object> metadata) {
        requireDatabase();
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, ip, trace_id, metadata)
                            VALUES (:userId, :action, :targetType, :targetId, :ip, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("userId", userId)
                    .param("action", action)
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("ip", ip)
                    .param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit metadata", ex);
        }
    }

    public void writeOutbox(Long postId, String action, String status, String topic) {
        requireDatabase();
        try {
            Long receiverId = jdbcClient.sql("SELECT author_id FROM post WHERE id = :postId")
                    .param("postId", postId)
                    .query(Long.class)
                    .single();
            EventEnvelope event = EventEnvelope.now(topic, "dc-admin", String.valueOf(postId),
                    Map.of("postId", postId, "receiverId", receiverId, "action", action, "status", status));
            String payload = objectMapper.writeValueAsString(event);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST_AUDIT', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateId", String.valueOf(postId))
                    .param("payload", payload)
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize moderation event", ex);
        }
    }

    public JdbcClient jdbcClient() {
        return jdbcClient;
    }

    private record IdempotencyRecord(Long actorId, String operation, String requestFingerprint, Long resourceId) {
    }
}
