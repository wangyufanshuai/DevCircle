package io.github.devcircle.messaging.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;

public class OutboxStatusService {
    private final JdbcClient jdbcClient;
    private final String serviceName;

    public OutboxStatusService(JdbcClient jdbcClient, String serviceName) {
        this.jdbcClient = jdbcClient;
        this.serviceName = serviceName;
    }

    public OutboxStatusView snapshot() {
        long retryable = count("SELECT COUNT(*) FROM outbox_event WHERE status IN ('NEW', 'FAILED', 'PROCESSING')");
        long dead = count("SELECT COUNT(*) FROM outbox_event WHERE status = 'DEAD'");
        long blockedByDead = count("""
                SELECT COUNT(*)
                FROM outbox_event current_event
                WHERE current_event.status <> 'PUBLISHED'
                  AND EXISTS (
                      SELECT 1 FROM outbox_event prior_event
                      WHERE prior_event.topic = current_event.topic
                        AND prior_event.aggregate_id = current_event.aggregate_id
                        AND prior_event.id < current_event.id
                        AND prior_event.status = 'DEAD'
                  )
                """);
        return new OutboxStatusView(serviceName, retryable + dead, retryable, dead, blockedByDead);
    }

    private long count(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }
}
