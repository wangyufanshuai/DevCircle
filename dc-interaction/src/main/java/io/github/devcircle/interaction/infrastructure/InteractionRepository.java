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
    public InteractionWriteResult insertLike(Long userId, Long postId, long baselineLikeCount,
                                             long receiverId, String idempotencyKey) {
        requireValidReceiver(receiverId);
        if (claimIdempotency(userId, "LIKE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        ensureLikeCounter(postId, baselineLikeCount);
        int rows = jdbcClient.sql("INSERT IGNORE INTO like_record(user_id, target_type, target_id) VALUES (:userId, 'POST', :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("""
                            UPDATE post_counter
                            SET like_count = like_count + 1, projection_version = projection_version + 1
                            WHERE post_id = :postId
                            """)
                    .param("postId", postId)
                    .update();
            writeEvent(userId, receiverId, postId, "LIKE");
        }
        attachResource(idempotencyKey, postId);
        return new InteractionWriteResult(rows > 0, false);
    }

    @Transactional
    public InteractionWriteResult insertFavorite(Long userId, Long postId, long baselineLikeCount,
                                                 long receiverId, String idempotencyKey) {
        requireValidReceiver(receiverId);
        if (claimIdempotency(userId, "FAVORITE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        ensureLikeCounter(postId, baselineLikeCount);
        int rows = jdbcClient.sql("INSERT IGNORE INTO favorite_record(user_id, post_id) VALUES (:userId, :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("""
                            INSERT INTO post_counter(post_id, favorite_count, projection_version)
                            VALUES (:postId, 1, 1)
                            ON DUPLICATE KEY UPDATE
                                favorite_count = favorite_count + 1,
                                projection_version = projection_version + 1
                            """)
                    .param("postId", postId)
                    .update();
            writeEvent(userId, receiverId, postId, "FAVORITE");
        }
        attachResource(idempotencyKey, postId);
        return new InteractionWriteResult(rows > 0, false);
    }

    @Transactional
    public InteractionWriteResult deleteLike(Long userId, Long postId, long baselineLikeCount,
                                             long receiverId, String idempotencyKey) {
        requireValidReceiver(receiverId);
        if (claimIdempotency(userId, "UNLIKE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        ensureLikeCounter(postId, baselineLikeCount);
        int rows = jdbcClient.sql("DELETE FROM like_record WHERE user_id = :userId AND target_type = 'POST' AND target_id = :postId")
                .param("userId", userId).param("postId", postId).update();
        if (rows > 0) {
            jdbcClient.sql("""
                            UPDATE post_counter
                            SET like_count = GREATEST(like_count - 1, 0),
                                projection_version = projection_version + 1
                            WHERE post_id = :postId
                            """)
                    .param("postId", postId).update();
            writeEvent(userId, receiverId, postId, "UNLIKE");
        }
        attachResource(idempotencyKey, postId);
        return new InteractionWriteResult(rows > 0, false);
    }

    @Transactional
    public InteractionWriteResult deleteFavorite(Long userId, Long postId, long baselineLikeCount,
                                                 long receiverId, String idempotencyKey) {
        requireValidReceiver(receiverId);
        if (claimIdempotency(userId, "UNFAVORITE", postId, idempotencyKey)) {
            return new InteractionWriteResult(false, true);
        }
        ensureLikeCounter(postId, baselineLikeCount);
        int rows = jdbcClient.sql("DELETE FROM favorite_record WHERE user_id = :userId AND post_id = :postId")
                .param("userId", userId).param("postId", postId).update();
        if (rows > 0) {
            jdbcClient.sql("""
                            UPDATE post_counter
                            SET favorite_count = GREATEST(favorite_count - 1, 0),
                                projection_version = projection_version + 1
                            WHERE post_id = :postId
                            """)
                    .param("postId", postId).update();
            writeEvent(userId, receiverId, postId, "UNFAVORITE");
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

    private void requireValidReceiver(long receiverId) {
        if (receiverId <= 0) {
            throw new IllegalArgumentException("Interaction receiverId must be positive");
        }
    }

    private void attachResource(String key, Long resourceId) {
        jdbcClient.sql("UPDATE idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                .param("resourceId", resourceId)
                .param("key", key)
                .update();
    }

    private void ensureLikeCounter(Long postId, long baselineLikeCount) {
        jdbcClient.sql("""
                        INSERT INTO post_counter(post_id, like_count)
                        VALUES (:postId, :likeCount)
                        ON DUPLICATE KEY UPDATE post_id = post_counter.post_id
                        """)
                .param("postId", postId)
                .param("likeCount", Math.max(0, baselineLikeCount))
                .update();
    }

    private void writeEvent(Long userId, long receiverId, Long postId, String interactionType) {
        CounterSnapshot snapshot = jdbcClient.sql("""
                        SELECT like_count, favorite_count, projection_version
                        FROM post_counter WHERE post_id = :postId
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new CounterSnapshot(
                        rs.getLong("like_count"), rs.getLong("favorite_count"),
                        rs.getLong("projection_version")))
                .single();
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", String.valueOf(postId),
                        Map.of("actorId", userId, "receiverId", receiverId, "postId", postId,
                        "interactionType", interactionType,
                        "likeCount", snapshot.likeCount(), "favoriteCount", snapshot.favoriteCount(),
                        "counterVersion", snapshot.version()));
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

    public java.util.List<Long> favoritePostIds(Long userId, int size) {
        return jdbcClient.sql("""
                        SELECT post_id FROM favorite_record
                        WHERE user_id = :userId ORDER BY created_at DESC LIMIT :size
                        """)
                .param("userId", userId).param("size", Math.max(1, Math.min(size, 100)))
                .query(Long.class).list();
    }

    public InteractionState interactionState(Long userId, Long postId) {
        Integer liked = jdbcClient.sql("SELECT COUNT(*) FROM like_record WHERE user_id = :userId AND target_type = 'POST' AND target_id = :postId")
                .param("userId", userId).param("postId", postId).query(Integer.class).single();
        Integer favorited = jdbcClient.sql("SELECT COUNT(*) FROM favorite_record WHERE user_id = :userId AND post_id = :postId")
                .param("userId", userId).param("postId", postId).query(Integer.class).single();
        InteractionCounts counts = jdbcClient.sql("SELECT like_count, favorite_count FROM post_counter WHERE post_id = :postId")
                .param("postId", postId)
                .query((rs, rowNum) -> new InteractionCounts(rs.getLong("like_count"), rs.getLong("favorite_count")))
                .optional()
                .orElse(null);
        return new InteractionState(liked != null && liked > 0, favorited != null && favorited > 0,
                counts == null ? null : counts.likeCount(), counts == null ? null : counts.favoriteCount());
    }

    public record InteractionState(boolean liked, boolean favorited, Long likeCount, Long favoriteCount) {
    }

    private record InteractionCounts(long likeCount, long favoriteCount) {
    }

    private record CounterSnapshot(long likeCount, long favoriteCount, long version) {
    }

    private record Idempotency(Long userId, String operation, Long resourceId) {
    }
}
