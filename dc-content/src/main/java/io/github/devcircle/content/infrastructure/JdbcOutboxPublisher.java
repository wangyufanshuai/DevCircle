package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.OutboxPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

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
            try {
                kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload()).get();
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Failed to publish outbox event " + event.id(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing outbox event " + event.id(), ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Failed to publish outbox event " + event.id(), ex);
            }
            jdbcClient.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", event.id())
                    .update();
        }
        return events.size();
    }

    private record EventRow(Long id, String topic, String aggregateId, String payload) {
    }
}
