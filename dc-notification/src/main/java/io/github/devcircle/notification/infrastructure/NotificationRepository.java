package io.github.devcircle.notification.infrastructure;

import io.github.devcircle.common.model.NotificationView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;

@Repository
public class NotificationRepository {
    private final JdbcClient jdbcClient;

    public NotificationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<NotificationView> list(Long receiverId, String type, Long beforeId, int size) {
        return jdbcClient.sql("""
                        SELECT id, type, title, content, read_flag, actor_id, target_type, target_id, target_route, created_at
                        FROM notification
                        WHERE receiver_id = :receiverId
                          AND (:type IS NULL OR type = :type)
                          AND (:beforeId IS NULL OR id < :beforeId)
                        ORDER BY id DESC
                        LIMIT :size
                        """)
                .param("receiverId", receiverId)
                .param("type", type == null || type.isBlank() ? null : type.toUpperCase())
                .param("beforeId", beforeId)
                .param("size", Math.max(1, Math.min(size, 51)))
                .query((rs, rowNum) -> new NotificationView(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getBoolean("read_flag"),
                        (Long) rs.getObject("actor_id"),
                        rs.getString("target_type"),
                        (Long) rs.getObject("target_id"),
                        rs.getString("target_route"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public List<NotificationView> list(Long receiverId) {
        return list(receiverId, null, null, 50);
    }

    public long unreadCount(Long receiverId) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM notification WHERE receiver_id = :receiverId AND read_flag = FALSE")
                .param("receiverId", receiverId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    @Transactional
    public boolean createOnce(String eventId, String topic, Long receiverId, Long actorId, String type,
                              String title, String content, String targetType, Long targetId, String targetRoute) {
        if (!claimConsumerEvent(eventId, topic)) return false;
        jdbcClient.sql("""
                        INSERT INTO notification(receiver_id, actor_id, type, title, content, target_type, target_id, target_route, read_flag)
                        VALUES (:receiverId, :actorId, :type, :title, :content, :targetType, :targetId, :targetRoute, false)
                        """)
                .param("receiverId", receiverId)
                .param("actorId", actorId)
                .param("type", type)
                .param("title", title)
                .param("content", content)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("targetRoute", targetRoute)
                .update();
        return true;
    }

    public boolean acknowledgeOnce(String eventId, String topic) {
        return claimConsumerEvent(eventId, topic);
    }

    private boolean claimConsumerEvent(String eventId, String topic) {
        int claimed = jdbcClient.sql("INSERT IGNORE INTO consumer_dedup(event_id, topic) VALUES (:eventId, :topic)")
                .param("eventId", eventId)
                .param("topic", topic)
                .update();
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

    @Transactional
    public long markAllRead(Long receiverId, String idempotencyKey) {
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO notification_idempotency_record(idempotency_key, user_id, operation, resource_id)
                        VALUES (:key, :userId, 'MARK_ALL_READ', 0)
                        """)
                .param("key", idempotencyKey).param("userId", receiverId).update();
        if (claimed == 0) {
            Idempotency existing = jdbcClient.sql("""
                            SELECT user_id, operation, resource_id FROM notification_idempotency_record
                            WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> new Idempotency(rs.getLong("user_id"), rs.getString("operation"), rs.getLong("resource_id")))
                    .single();
            if (!existing.userId().equals(receiverId) || !"MARK_ALL_READ".equals(existing.operation())) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已被其他操作使用");
            }
            return 0;
        }
        return jdbcClient.sql("UPDATE notification SET read_flag = TRUE WHERE receiver_id = :userId AND read_flag = FALSE")
                .param("userId", receiverId).update();
    }

    @Transactional
    public boolean markRead(Long receiverId, Long notificationId, String idempotencyKey) {
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO notification_idempotency_record(idempotency_key, user_id, operation, resource_id)
                        VALUES (:key, :userId, 'MARK_READ', :notificationId)
                        """)
                .param("key", idempotencyKey)
                .param("userId", receiverId)
                .param("notificationId", notificationId)
                .update();
        if (claimed == 0) {
            Idempotency existing = jdbcClient.sql("""
                            SELECT user_id, operation, resource_id FROM notification_idempotency_record
                            WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> new Idempotency(rs.getLong("user_id"), rs.getString("operation"), rs.getLong("resource_id")))
                    .single();
            if (!existing.userId().equals(receiverId) || !"MARK_READ".equals(existing.operation()) || !existing.resourceId().equals(notificationId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已被其他操作使用");
            }
            return false;
        }
        int updated = jdbcClient.sql("UPDATE notification SET read_flag = TRUE WHERE id = :id AND receiver_id = :userId")
                .param("id", notificationId)
                .param("userId", receiverId)
                .update();
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "通知不存在");
        }
        return true;
    }

    private record Idempotency(Long userId, String operation, Long resourceId) {
    }
}
