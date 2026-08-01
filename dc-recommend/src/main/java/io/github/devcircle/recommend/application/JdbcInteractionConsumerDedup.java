package io.github.devcircle.recommend.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInteractionConsumerDedup implements InteractionConsumerDedup {
    private final JdbcClient jdbcClient;

    JdbcInteractionConsumerDedup(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean claim(String eventId, String topic) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO recommend_consumer_dedup(event_id, topic, status, attempts)
                        VALUES (:eventId, :topic, 'PROCESSING', 1)
                        """)
                .param("eventId", eventId).param("topic", topic).update();
        if (inserted == 1) return true;

        DedupRecord existing = jdbcClient.sql("""
                        SELECT topic, status
                        FROM recommend_consumer_dedup
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new DedupRecord(rs.getString("topic"), rs.getString("status")))
                .single();
        if (!existing.topic().equals(topic)) {
            throw new IllegalArgumentException("Event ID is already associated with topic " + existing.topic());
        }

        int reclaimed = jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'PROCESSING', attempts = attempts + 1, last_error = NULL
                        WHERE event_id = :eventId AND topic = :topic AND status = 'FAILED'
                        """)
                .param("eventId", eventId).param("topic", topic).update();
        if (reclaimed == 1) return true;
        return "PROCESSING".equals(existing.status()) && jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'PROCESSING', attempts = attempts + 1
                        WHERE event_id = :eventId AND topic = :topic AND status = 'PROCESSING'
                          AND updated_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE
                        """).param("eventId", eventId).param("topic", topic).update() == 1;
    }

    @Override
    public void markCompleted(String eventId) {
        jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP, last_error = NULL
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId).update();
    }

    @Override
    public void markFailed(String eventId, String message) {
        String safeMessage = message == null ? "unknown failure" : message;
        jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'FAILED', last_error = :lastError
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .param("lastError", safeMessage.substring(0, Math.min(1000, safeMessage.length())))
                .update();
    }

    private record DedupRecord(String topic, String status) {
    }
}
