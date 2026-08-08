package io.github.devcircle.admin.infrastructure;

import io.github.devcircle.admin.application.query.AdminOpsReadRepository;
import io.github.devcircle.admin.application.query.OutboxEventView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcAdminOpsReadRepository implements AdminOpsReadRepository {
    private final JdbcClient jdbcClient;

    public JdbcAdminOpsReadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public HealthMetrics healthMetrics() {
        return new HealthMetrics(
                oneLong("SELECT COUNT(*) FROM post"),
                oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'"),
                oneLong("SELECT COUNT(*) FROM user_risk_profile WHERE status <> 'NORMAL'"));
    }

    @Override
    public DegradationMetrics degradationMetrics() {
        return new DegradationMetrics(
                oneLong("SELECT COUNT(*) FROM recommendation_impression"),
                oneLong("SELECT COUNT(*) FROM recommend_event_log"),
                oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'"));
    }

    @Override
    public List<OutboxEventView> outboxEvents(String status, String topic, int size, long offset) {
        return jdbcClient.sql("""
                        SELECT id, event_id, topic, aggregate_type, aggregate_id, status, attempts,
                               next_attempt_at, locked_at, last_error, created_at, published_at
                        FROM outbox_event
                        WHERE (:status IS NULL OR status = :status)
                          AND (:topic IS NULL OR topic = :topic)
                        ORDER BY created_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("status", status)
                .param("topic", topic)
                .param("size", size)
                .param("offset", offset)
                .query((rs, rowNum) -> {
                    Timestamp nextAttemptAt = rs.getTimestamp("next_attempt_at");
                    Timestamp lockedAt = rs.getTimestamp("locked_at");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    Timestamp publishedAt = rs.getTimestamp("published_at");
                    return new OutboxEventView(
                            rs.getLong("id"),
                            rs.getString("event_id"),
                            rs.getString("topic"),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            nextAttemptAt == null ? null : nextAttemptAt.toInstant(),
                            lockedAt == null ? null : lockedAt.toInstant(),
                            rs.getString("last_error"),
                            createdAt == null ? null : createdAt.toInstant(),
                            publishedAt == null ? null : publishedAt.toInstant());
                })
                .list();
    }

    private long oneLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }
}
