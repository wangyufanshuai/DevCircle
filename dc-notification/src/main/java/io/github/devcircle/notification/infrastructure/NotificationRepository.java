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

    public List<NotificationView> list(Long receiverId) {
        return jdbcClient.sql("""
                        SELECT id, type, title, content, read_flag, created_at
                        FROM notification
                        WHERE receiver_id = :receiverId
                        ORDER BY created_at DESC
                        LIMIT 50
                        """)
                .param("receiverId", receiverId)
                .query((rs, rowNum) -> new NotificationView(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getBoolean("read_flag"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public boolean createOnce(String eventId, String topic, Long receiverId, String type, String title, String content) {
        int claimed = jdbcClient.sql("INSERT IGNORE INTO consumer_dedup(event_id, topic) VALUES (:eventId, :topic)")
                .param("eventId", eventId)
                .param("topic", topic)
                .update();
        if (claimed == 0) {
            return false;
        }
        jdbcClient.sql("""
                        INSERT INTO notification(receiver_id, type, title, content, read_flag)
                        VALUES (:receiverId, :type, :title, :content, false)
                        """)
                .param("receiverId", receiverId)
                .param("type", type)
                .param("title", title)
                .param("content", content)
                .update();
        return true;
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
