package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.FeedItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Repository
class RecommendationImpressionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final boolean readFallbackEnabled;

    RecommendationImpressionRepository(JdbcClient jdbcClient,
                                       ObjectMapper objectMapper,
                                       @Value("${devcircle.recommend.read-fallback-enabled:false}") boolean readFallbackEnabled) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.readFallbackEnabled = readFallbackEnabled;
    }

    void save(Long userId, String scene, FeedItem item) {
        try {
            jdbcClient.sql("""
                            INSERT INTO recommendation_impression(user_id, post_id, scene, algorithm, score, features)
                            VALUES (:userId, :postId, :scene, :algorithm, :score, CAST(:features AS JSON))
                            """)
                    .param("userId", userId)
                    .param("postId", item.post().id())
                    .param("scene", scene)
                    .param("algorithm", item.algorithm())
                    .param("score", item.score())
                    .param("features", objectMapper.writeValueAsString(item.features()))
                    .update();
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) throw dependency("推荐曝光记录暂时不可用", ex);
        } catch (JsonProcessingException ex) {
            throw dependency("推荐曝光记录暂时不可用", ex);
        }
    }

    void saveAll(Long userId, String scene, List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            StringBuilder sql = new StringBuilder("""
                    INSERT INTO recommendation_impression(user_id, post_id, scene, algorithm, score, features)
                    VALUES
                    """);
            List<String> features = new ArrayList<>(items.size());
            for (int index = 0; index < items.size(); index++) {
                if (index > 0) sql.append(',');
                sql.append("(:userId, :postId").append(index)
                        .append(", :scene, :algorithm").append(index)
                        .append(", :score").append(index)
                        .append(", CAST(:features").append(index).append(" AS JSON))");
                features.add(objectMapper.writeValueAsString(items.get(index).features()));
            }
            JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString())
                    .param("userId", userId)
                    .param("scene", scene);
            for (int index = 0; index < items.size(); index++) {
                FeedItem item = items.get(index);
                statement = statement
                        .param("postId" + index, item.post().id())
                        .param("algorithm" + index, item.algorithm())
                        .param("score" + index, item.score())
                        .param("features" + index, features.get(index));
            }
            statement.update();
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) throw dependency("推荐曝光记录暂时不可用", ex);
        } catch (JsonProcessingException ex) {
            throw dependency("推荐曝光记录暂时不可用", ex);
        }
    }

    Map<String, Object> activeExperiment(String scene, Long userId) {
        try {
            Map<String, Object> experiment = jdbcClient.sql("""
                            SELECT experiment_id, scene, status, traffic_percent, weights
                            FROM recommend_experiment
                            WHERE status = 'ACTIVE' AND scene IN (:scene, 'home')
                            ORDER BY CASE WHEN scene = :scene THEN 0 ELSE 1 END, updated_at DESC
                            LIMIT 1
                            """)
                    .param("scene", scene)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "experimentId", rs.getString("experiment_id"),
                            "scene", rs.getString("scene"),
                            "trafficPercent", rs.getInt("traffic_percent"),
                            "weights", rs.getString("weights")
                    ))
                    .single();
            String bucket = Math.floorMod(userId.hashCode(), 100) < 50 ? "control" : "treatment";
            return Map.of("experimentId", experiment.get("experimentId"), "bucket", bucket);
        } catch (DataAccessException ex) {
            if (!readFallbackEnabled) throw dependency("推荐实验暂时不可用", ex);
            return Map.of("experimentId", "exp-theme-feed-v1", "bucket", Math.floorMod(userId.hashCode(), 100) < 50 ? "control" : "treatment");
        }
    }

    @Transactional
    int saveExposure(Long userId, List<Long> postIds, String scene, String idempotencyKey) {
        List<Long> normalizedPostIds = postIds.stream().distinct().sorted().limit(100).toList();
        String fingerprint = fingerprint(userId, scene, normalizedPostIds);
        if (!claim(userId, "EXPOSURE", idempotencyKey, fingerprint)) return 0;
        int recorded = 0;
        for (Long postId : normalizedPostIds) {
            jdbcClient.sql("""
                            INSERT INTO recommendation_exposure(user_id, post_id, scene)
                            VALUES (:userId, :postId, :scene)
                            ON DUPLICATE KEY UPDATE exposed_at = CURRENT_TIMESTAMP
                            """)
                    .param("userId", userId).param("postId", postId).param("scene", scene).update();
            recorded++;
        }
        return recorded;
    }

    @Transactional
    void saveRecommendEvent(Long userId, Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
        String fingerprint = fingerprint(userId, postId, eventType, scene, experimentId, bucket);
        if (!claim(userId, "RECOMMEND_EVENT", requestId, fingerprint)) return;
        try {
            jdbcClient.sql("""
                            INSERT INTO recommend_event_log(user_id, post_id, event_type, scene, experiment_id, bucket, request_id)
                            VALUES (:userId, :postId, :eventType, :scene, :experimentId, :bucket, :requestId)
                            """)
                    .param("userId", userId)
                    .param("postId", postId)
                    .param("eventType", eventType)
                    .param("scene", scene)
                    .param("experimentId", experimentId)
                    .param("bucket", bucket)
                    .param("requestId", requestId)
                    .update();
            writeOutbox(EventTopics.RECOMMEND_EVENT_LOGGED, Map.of(
                    "userId", userId,
                    "postId", postId,
                    "eventType", eventType,
                    "scene", scene,
                    "experimentId", experimentId,
                    "bucket", bucket,
                    "requestId", requestId == null ? "" : requestId
            ));
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    private void writeOutbox(String topic, Map<String, Object> payload) {
        try {
            EventEnvelope event = EventEnvelope.now(topic, "dc-discovery", String.valueOf(payload.get("postId")), payload);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'RECOMMEND_EVENT', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateId", String.valueOf(payload.get("postId")))
                    .param("payload", objectMapper.writeValueAsString(event))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize recommendation event", ex);
        }
    }

    private boolean claim(Long userId, String operation, String key, String requestFingerprint) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO recommend_write_idempotency(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES (:key, :userId, :operation, :requestFingerprint)
                        """)
                .param("key", key).param("userId", userId).param("operation", operation)
                .param("requestFingerprint", requestFingerprint).update();
        if (inserted == 1) return true;
        Map<String, Object> existing = jdbcClient.sql("""
                        SELECT user_id, operation, request_fingerprint
                        FROM recommend_write_idempotency WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "userId", rs.getLong("user_id"),
                        "operation", rs.getString("operation"),
                        "requestFingerprint", rs.getString("request_fingerprint")))
                .single();
        if (!existing.get("userId").equals(userId)
                || !existing.get("operation").equals(operation)
                || !existing.get("requestFingerprint").equals(requestFingerprint)) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已被其他推荐写操作使用");
        }
        return false;
    }

    private String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            canonical.append(text.length()).append(':').append(text).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BusinessException dependency(String message, Exception cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }
}
