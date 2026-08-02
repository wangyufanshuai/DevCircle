package io.github.devcircle.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ReliableOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(ReliableOutboxWorker.class);
    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String workerId = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private final int batchSize;
    private final int maxAttempts;
    private final long sendTimeoutMs;

    public ReliableOutboxWorker(JdbcClient jdbcClient,
                                KafkaTemplate<String, String> kafkaTemplate,
                                @Value("${devcircle.outbox.batch-size:50}") int batchSize,
                                @Value("${devcircle.outbox.max-attempts:8}") int maxAttempts) {
        this(jdbcClient, kafkaTemplate, batchSize, maxAttempts, 15_000L);
    }

    public ReliableOutboxWorker(JdbcClient jdbcClient,
                                KafkaTemplate<String, String> kafkaTemplate,
                                int batchSize,
                                int maxAttempts,
                                long sendTimeoutMs) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.maxAttempts = Math.max(1, maxAttempts);
        this.sendTimeoutMs = Math.max(1, sendTimeoutMs);
    }

    @Scheduled(
            fixedDelayString = "${devcircle.outbox.poll-interval-ms:1000}",
            initialDelayString = "${devcircle.outbox.initial-delay-ms:0}")
    public void publishAvailable() {
        runBatch(batchSize, false);
    }

    /** Publishes through the same lease/retry state machine for an operator-triggered replay. */
    public int publishBatch(int requestedBatchSize) {
        return runBatch(Math.max(1, Math.min(requestedBatchSize, 500)), true);
    }

    private int runBatch(int requestedBatchSize, boolean failFast) {
        if (!running.compareAndSet(false, true)) {
            if (failFast) throw new IllegalStateException("Outbox publisher is already running");
            return 0;
        }
        int published = 0;
        try {
            for (EventRow event : candidates(requestedBatchSize)) {
                if (claim(event.id()) == 1) {
                    if (publish(event)) {
                        published++;
                    } else if (failFast) {
                        throw new IllegalStateException("Failed to publish outbox event " + event.id()
                                + "; event remains available for retry");
                    }
                }
            }
        } catch (BadSqlGrammarException ex) {
            if (failFast) throw ex;
            log.debug("Outbox table is not available for this service: {}", ex.getMostSpecificCause().getMessage());
        } finally {
            running.set(false);
        }
        return published;
    }

    private List<EventRow> candidates(int requestedBatchSize) {
        return jdbcClient.sql("""
                        SELECT id, event_id, topic, aggregate_id, CAST(payload AS CHAR) payload, attempts
                        FROM outbox_event current_event
                        WHERE ((current_event.status IN ('NEW', 'FAILED')
                                  AND (current_event.next_attempt_at IS NULL OR current_event.next_attempt_at <= CURRENT_TIMESTAMP))
                           OR (current_event.status = 'PROCESSING'
                                  AND (current_event.locked_at IS NULL
                                       OR current_event.locked_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP))))
                          AND NOT EXISTS (
                              SELECT 1
                              FROM outbox_event prior_event
                              WHERE prior_event.topic = current_event.topic
                                AND prior_event.aggregate_id = current_event.aggregate_id
                                AND prior_event.id < current_event.id
                                AND prior_event.status <> 'PUBLISHED'
                          )
                        ORDER BY current_event.created_at, current_event.id
                        LIMIT :batchSize
                        """)
                .param("batchSize", requestedBatchSize)
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
                          AND ((status IN ('NEW', 'FAILED') AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                            OR (status = 'PROCESSING' AND (locked_at IS NULL
                                OR locked_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP))))
                        """)
                .param("workerId", workerId)
                .param("id", id)
                .update();
    }

    private boolean publish(EventRow event) {
        try {
            kafkaTemplate.send(event.topic(), event.aggregateId(), event.payload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            int updated = jdbcClient.sql("""
                            UPDATE outbox_event
                            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                                locked_at = NULL, locked_by = NULL, last_error = NULL
                            WHERE id = :id AND locked_by = :workerId
                            """)
                    .param("id", event.id())
                    .param("workerId", workerId)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("Outbox lease lost before marking event PUBLISHED");
            }
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(event, ex);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            fail(event, ex);
        }
        return false;
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
                .param("status", dead ? "DEAD" : "FAILED")
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
