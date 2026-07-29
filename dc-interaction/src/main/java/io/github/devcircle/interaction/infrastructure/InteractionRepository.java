package io.github.devcircle.interaction.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Repository
public class InteractionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public InteractionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InteractionWriteResult insertLike(Long userId, Long postId, String idempotencyKey) {
        if (claimIdempotency(userId, "LIKE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        int rows = jdbcClient.sql("INSERT IGNORE INTO like_record(user_id, target_type, target_id) VALUES (:userId, 'POST', :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("INSERT INTO post_counter(post_id, like_count) VALUES (:postId, 1) ON DUPLICATE KEY UPDATE like_count = like_count + 1")
                    .param("postId", postId)
                    .update();
            writeEvent(userId, postId, "LIKE");
        }
        attachResource(idempotencyKey, postId);
        return new InteractionWriteResult(rows > 0, false);
    }

    @Transactional
    public InteractionWriteResult insertFavorite(Long userId, Long postId, String idempotencyKey) {
        if (claimIdempotency(userId, "FAVORITE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        int rows = jdbcClient.sql("INSERT IGNORE INTO favorite_record(user_id, post_id) VALUES (:userId, :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("INSERT INTO post_counter(post_id, favorite_count) VALUES (:postId, 1) ON DUPLICATE KEY UPDATE favorite_count = favorite_count + 1")
                    .param("postId", postId)
                    .update();
            writeEvent(userId, postId, "FAVORITE");
        }
        attachResource(idempotencyKey, postId);
        return new InteractionWriteResult(rows > 0, false);
    }

    private boolean claimIdempotency(Long userId, String operation, Long resourceId, String key) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO idempotency_record(idempotency_key, user_id, operation, resource_id)
                        VALUES (:key, :userId, :operation, :resourceId)
                        """)
                .param("key", key)
                .param("userId", userId)
                .param("operation", operation)
                .param("resourceId", resourceId)
                .update();
        if (inserted == 1) {
            return false;
        }
        Idempotency existing = jdbcClient.sql("""
                        SELECT user_id, operation, resource_id FROM idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new Idempotency(rs.getLong("user_id"), rs.getString("operation"), rs.getLong("resource_id")))
                .single();
        if (!existing.userId().equals(userId) || !existing.operation().equals(operation) || !existing.resourceId().equals(resourceId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他请求");
        }
        return true;
    }

    private void attachResource(String key, Long resourceId) {
        jdbcClient.sql("UPDATE idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                .param("resourceId", resourceId)
                .param("key", key)
                .update();
    }

    private void writeEvent(Long userId, Long postId, String interactionType) {
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", String.valueOf(postId),
                Map.of("actorId", userId, "postId", postId, "interactionType", interactionType));
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateId", event.aggregateId())
                    .param("payload", payload)
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize interaction event", ex);
        }
    }

    public record InteractionWriteResult(boolean inserted, boolean duplicateRequest) {
    }

    private record Idempotency(Long userId, String operation, Long resourceId) {
    }
}
