package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.model.FeedItem;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
}
