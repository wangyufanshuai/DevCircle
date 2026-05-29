package io.github.devcircle.notification.infrastructure;

import io.github.devcircle.common.model.NotificationView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    public void create(Long receiverId, String type, String title, String content) {
        jdbcClient.sql("""
                        INSERT INTO notification(receiver_id, type, title, content, read_flag)
                        VALUES (:receiverId, :type, :title, :content, false)
                        """)
                .param("receiverId", receiverId)
                .param("type", type)
                .param("title", title)
                .param("content", content)
                .update();
    }
}
