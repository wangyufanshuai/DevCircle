package io.github.devcircle.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReliableOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(ReliableOutboxWorker.class);
    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String workerId = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private final int batchSize;
    private final int maxAttempts;

    public ReliableOutboxWorker(JdbcClient jdbcClient,
                                KafkaTemplate<String, String> kafkaTemplate,
                                @Value("${devcircle.outbox.batch-size:50}") int batchSize,
                                @Value("${devcircle.outbox.max-attempts:8}") int maxAttempts) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${devcircle.outbox.poll-interval-ms:1000}")
    public void publishAvailable() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (EventRow event : candidates()) {
                if (claim(event.id()) == 1) {
                    publish(event);
                }
            }
        } catch (BadSqlGrammarException ex) {
            log.debug("Outbox table is not available for this service: {}", ex.getMostSpecificCause().getMessage());
        } finally {
            running.set(false);
        }
    }

    private List<EventRow> candidates() {
        return jdbcClient.sql("""
                        SELECT id, event_id, topic, aggregate_id, CAST(payload AS CHAR) payload, attempts
                        FROM outbox_event
                        WHERE (status = 'NEW' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                           OR (status = 'PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE)
                        ORDER BY created_at
                        LIMIT :batchSize
                        """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new EventRow(
                        rs.getLong("id"), rs.getString("event_id"), rs.getString("topic"),
                        rs.getString("aggregate_id"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    private int claim(Long id) {
        return jdbcClient.sql("""
                        UPDATE outbox_event
                        SET status = 'PROCESSING', locked_at = CURRENT_TIMESTAMP, locked_by = :workerId
                        WHERE id = :id
                          AND ((status = 'NEW' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                            OR (status = 'PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE))
                        """)
                .param("workerId", workerId)
                .param("id", id)
                .update();
    }

    private void publish(EventRow event) {
        try {
            kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload()).get();
            jdbcClient.sql("""
                            UPDATE outbox_event
                            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                                locked_at = NULL, locked_by = NULL, last_error = NULL
                            WHERE id = :id AND locked_by = :workerId
                            """)
                    .param("id", event.id())
                    .param("workerId", workerId)
                    .update();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(event, ex);
        } catch (ExecutionException | RuntimeException ex) {
            fail(event, ex);
        }
    }

    private void fail(EventRow event, Exception ex) {
        int attempts = event.attempts() + 1;
        boolean dead = attempts >= maxAttempts;
        int delaySeconds = Math.min(300, 1 << Math.min(attempts, 8));
        String message = ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage());
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        jdbcClient.sql("""
                        UPDATE outbox_event
                        SET status = :status, attempts = :attempts,
                            next_attempt_at = CASE WHEN :dead THEN NULL ELSE TIMESTAMPADD(SECOND, :delaySeconds, CURRENT_TIMESTAMP) END,
                            locked_at = NULL, locked_by = NULL, last_error = :lastError
                        WHERE id = :id AND locked_by = :workerId
                        """)
                .param("status", dead ? "DEAD" : "NEW")
                .param("attempts", attempts)
                .param("dead", dead)
                .param("delaySeconds", delaySeconds)
                .param("lastError", message)
                .param("id", event.id())
                .param("workerId", workerId)
                .update();
        log.warn("Outbox publish failed eventId={} topic={} attempts={} dead={}", event.eventId(), event.topic(), attempts, dead);
    }

    private record EventRow(Long id, String eventId, String topic, String aggregateId, String payload, int attempts) {
    }
}
