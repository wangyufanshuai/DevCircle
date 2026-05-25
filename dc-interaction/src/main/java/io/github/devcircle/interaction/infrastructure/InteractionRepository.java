package io.github.devcircle.interaction.infrastructure;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InteractionRepository {
    private final JdbcClient jdbcClient;

    public InteractionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean insertLike(Long userId, Long postId) {
        try {
            int rows = jdbcClient.sql("INSERT IGNORE INTO like_record(user_id, target_type, target_id) VALUES (:userId, 'POST', :postId)")
                    .param("userId", userId)
                    .param("postId", postId)
                    .update();
            if (rows > 0) {
                jdbcClient.sql("INSERT INTO post_counter(post_id, like_count) VALUES (:postId, 1) ON DUPLICATE KEY UPDATE like_count = like_count + 1")
                        .param("postId", postId)
                        .update();
            }
            return rows > 0;
        } catch (DataAccessException ex) {
            return true;
        }
    }

    public boolean insertFavorite(Long userId, Long postId) {
        try {
            int rows = jdbcClient.sql("INSERT IGNORE INTO favorite_record(user_id, post_id) VALUES (:userId, :postId)")
                    .param("userId", userId)
                    .param("postId", postId)
                    .update();
            if (rows > 0) {
                jdbcClient.sql("INSERT INTO post_counter(post_id, favorite_count) VALUES (:postId, 1) ON DUPLICATE KEY UPDATE favorite_count = favorite_count + 1")
                        .param("postId", postId)
                        .update();
            }
            return rows > 0;
        } catch (DataAccessException ex) {
            return true;
        }
    }
}

