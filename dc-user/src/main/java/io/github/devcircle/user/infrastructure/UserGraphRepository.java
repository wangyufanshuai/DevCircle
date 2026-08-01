package io.github.devcircle.user.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class UserGraphRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public UserGraphRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public boolean follow(Long userId, Long targetUserId) {
        int rows = jdbcClient.sql("INSERT IGNORE INTO user_follow(follower_id, followee_id) VALUES (:userId, :targetUserId)")
                .param("userId", userId)
                .param("targetUserId", targetUserId)
                .update();
        if (rows > 0) {
            jdbcClient.sql("UPDATE user_profile SET following_count = following_count + 1 WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
            jdbcClient.sql("UPDATE user_profile SET follower_count = follower_count + 1 WHERE user_id = :targetUserId")
                    .param("targetUserId", targetUserId)
                    .update();
        }
        return rows > 0;
    }

    public boolean claimOperation(Long userId, Long targetUserId, String operation, String idempotencyKey) {
        int rows = jdbcClient.sql("INSERT IGNORE INTO user_idempotency(user_id, idempotency_key, operation, target_user_id) VALUES (:userId, :key, :operation, :targetUserId)")
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("operation", operation)
                .param("targetUserId", targetUserId)
                .update();
        if (rows > 0) return true;
        IdempotencyRecord existing = jdbcClient.sql("SELECT operation, target_user_id FROM user_idempotency WHERE user_id = :userId AND idempotency_key = :key")
                .param("userId", userId)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new IdempotencyRecord(rs.getString("operation"), rs.getLong("target_user_id")))
                .single();
        if (!operation.equals(existing.operation()) || !targetUserId.equals(existing.targetUserId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他操作");
        }
        return false;
    }

    public boolean isFollowing(Long userId, Long targetUserId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM user_follow WHERE follower_id = :userId AND followee_id = :targetUserId")
                .param("userId", userId)
                .param("targetUserId", targetUserId)
                .query(Integer.class)
                .single() > 0;
    }

    public Optional<UserProfile> profile(Long userId) {
        return jdbcClient.sql("SELECT user_id, username, avatar_url, bio, follower_count, following_count FROM user_profile WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, rowNum) -> new UserProfile(rs.getLong("user_id"), rs.getString("username"), rs.getString("avatar_url"), rs.getString("bio"),
                        rs.getLong("follower_count"), rs.getLong("following_count")))
                .optional();
    }

    public List<UserProfile> profiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        List<Long> safeIds = userIds.stream().filter(id -> id != null && id > 0).distinct().limit(50).toList();
        if (safeIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                        SELECT user_id, username, avatar_url, bio, follower_count, following_count
                        FROM user_profile
                        WHERE user_id IN (:userIds)
                        ORDER BY user_id
                        """)
                .param("userIds", safeIds)
                .query((rs, rowNum) -> new UserProfile(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("avatar_url"), rs.getString("bio"), rs.getLong("follower_count"), rs.getLong("following_count")))
                .list();
    }

    public boolean unfollow(Long userId, Long targetUserId) {
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
        return rows > 0;
    }

    public UserProfile requireProfile(Long userId) {
        return profile(userId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在或资料尚未完成同步"));
    }

    @Transactional
    public boolean synchronizeProfile(String eventId, String topic, Long userId, String displayName) {
        if (!claimConsumerEvent(eventId, topic)) return false;
        jdbcClient.sql("INSERT IGNORE INTO user_profile(user_id, username) VALUES (:userId, :username)")
                .param("userId", userId).param("username", displayName).update();
        jdbcClient.sql("UPDATE user_profile SET username = :username WHERE user_id = :userId")
                .param("userId", userId).param("username", displayName).update();
        return true;
    }

    private boolean claimConsumerEvent(String eventId, String topic) {
        int claimed = jdbcClient.sql("INSERT IGNORE INTO consumer_dedup(event_id, topic) VALUES (:eventId, :topic)")
                .param("eventId", eventId).param("topic", topic).update();
        if (claimed == 1) return true;
        String existingTopic = jdbcClient.sql("SELECT topic FROM consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
        if (!topic.equals(existingTopic)) {
            throw new IllegalArgumentException("Event ID is already bound to topic " + existingTopic);
        }
        return false;
    }

    public void writeFollowChanged(Long actorId, UserProfile actor, Long receiverId, UserProfile receiver,
                                   boolean followed) {
        String status = followed ? "FOLLOWED" : "UNFOLLOWED";
        EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", receiverId.toString(),
                java.util.Map.of("actorId", actorId, "actorUsername", actor.username(),
                        "receiverId", receiverId, "receiverUsername", receiver.username(),
                        "targetType", "USER", "targetId", actorId, "targetRoute", "/me", "status", status));
        try {
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'USER', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString()).param("topic", event.type())
                    .param("aggregateId", event.aggregateId())
                    .param("payload", objectMapper.writeValueAsString(event)).update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize follow event", ex);
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

    private record IdempotencyRecord(String operation, Long targetUserId) {
    }

    public record UserProfile(Long userId, String username, String avatarUrl, String bio,
                              long followerCount, long followingCount) {
    }
}
