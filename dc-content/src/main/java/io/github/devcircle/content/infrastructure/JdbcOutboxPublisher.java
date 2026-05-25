package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.OutboxPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOutboxPublisher implements OutboxPublisher {
    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public JdbcOutboxPublisher(JdbcClient jdbcClient, KafkaTemplate<String, String> kafkaTemplate) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public int publishNewEvents(int batchSize) {
        var events = jdbcClient.sql("""
                        SELECT id, topic, aggregate_id, payload
                        FROM outbox_event
                        WHERE status = 'NEW'
                        ORDER BY created_at
                        LIMIT :batchSize
                        """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new EventRow(rs.getLong("id"), rs.getString("topic"), rs.getString("aggregate_id"), rs.getString("payload")))
                .list();
        for (EventRow event : events) {
            kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload());
            jdbcClient.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", event.id())
                    .update();
        }
        return events.size();
    }

    private record EventRow(Long id, String topic, String aggregateId, String payload) {
    }
}

