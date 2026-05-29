package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.model.FeedItem;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
class RecommendationImpressionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    RecommendationImpressionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
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
        } catch (DataAccessException | JsonProcessingException ignored) {
            // Recommendation logging must not block feed delivery.
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
            return Map.of("experimentId", "exp-theme-feed-v1", "bucket", Math.floorMod(userId.hashCode(), 100) < 50 ? "control" : "treatment");
        }
    }

    void saveRecommendEvent(Long userId, Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
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
        } catch (DataAccessException ignored) {
        }
    }

    private void writeOutbox(String topic, Map<String, Object> payload) {
        try {
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'RECOMMEND_EVENT', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", UUID.randomUUID().toString())
                    .param("topic", topic)
                    .param("aggregateId", String.valueOf(payload.get("postId")))
                    .param("payload", objectMapper.writeValueAsString(payload))
                    .update();
        } catch (DataAccessException | JsonProcessingException ignored) {
        }
    }
}
