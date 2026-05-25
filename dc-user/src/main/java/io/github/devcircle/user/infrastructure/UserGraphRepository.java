package io.github.devcircle.user.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserGraphRepository {
    private final JdbcClient jdbcClient;

    public UserGraphRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            return false;
        }
        int rows = jdbcClient.sql("INSERT IGNORE INTO user_follow(follower_id, followee_id) VALUES (:userId, :targetUserId)")
                .param("userId", userId)
                .param("targetUserId", targetUserId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("INSERT INTO user_profile(user_id, username, following_count) VALUES (:userId, :username, 1) ON DUPLICATE KEY UPDATE following_count = following_count + 1")
                    .param("userId", userId)
                    .param("username", "user-" + userId)
                    .update();
            jdbcClient.sql("INSERT INTO user_profile(user_id, username, follower_count) VALUES (:targetUserId, :username, 1) ON DUPLICATE KEY UPDATE follower_count = follower_count + 1")
                    .param("targetUserId", targetUserId)
                    .param("username", "user-" + targetUserId)
                    .update();
        }
        return rows > 0;
    }

    public void unfollow(Long userId, Long targetUserId) {
        int rows = jdbcClient.sql("DELETE FROM user_follow WHERE follower_id = :userId AND followee_id = :targetUserId")
                .param("userId", userId)
                .param("targetUserId", targetUserId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("UPDATE user_profile SET following_count = GREATEST(following_count - 1, 0) WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            jdbcClient.sql("UPDATE user_profile SET follower_count = GREATEST(follower_count - 1, 0) WHERE user_id = :targetUserId")
                    .param("targetUserId", targetUserId)
                    .update();
        }
    }

    public List<Long> followees(Long userId) {
        return jdbcClient.sql("SELECT followee_id FROM user_follow WHERE follower_id = :userId ORDER BY created_at DESC")
                .param("userId", userId)
                .query(Long.class)
                .list();
    }

    public List<Long> followers(Long userId) {
        return jdbcClient.sql("SELECT follower_id FROM user_follow WHERE followee_id = :userId ORDER BY created_at DESC")
                .param("userId", userId)
                .query(Long.class)
                .list();
    }

    public List<TwoHopUser> twoHopRecommendations(Long userId) {
        return jdbcClient.sql("""
                        SELECT f2.follower_id AS user_id, COUNT(*) AS mutual_count
                        FROM user_follow f1
                        JOIN user_follow f2 ON f2.followee_id = f1.followee_id
                        LEFT JOIN user_follow existing ON existing.follower_id = :userId AND existing.followee_id = f2.follower_id
                        WHERE f1.follower_id = :userId
                          AND f2.follower_id <> :userId
                          AND existing.id IS NULL
                        GROUP BY f2.follower_id
                        ORDER BY mutual_count DESC, f2.follower_id
                        LIMIT 20
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new TwoHopUser(rs.getLong("user_id"), rs.getLong("mutual_count")))
                .list();
    }

    public record TwoHopUser(Long userId, long mutualFollowees) {
    }
}
