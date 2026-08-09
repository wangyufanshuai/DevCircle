package io.github.devcircle.messaging.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReliableOutboxWorkerTest {
    private JdbcClient jdbc;
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reliable-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DROP TABLE IF EXISTS outbox_event").update();
        jdbc.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id VARCHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload VARCHAR(4000) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP NULL,
                    locked_at TIMESTAMP NULL,
                    locked_by VARCHAR(64) NULL,
                    last_error VARCHAR(1000) NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    published_at TIMESTAMP NULL
                )
                """).update();
        kafka = mock(KafkaTemplate.class);
    }

    @Test
    void failedPublishIsRetryableThenBecomesPublished() {
        insert("evt-retry", "NEW", 0, null);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(null));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThatThrownBy(() -> worker.publishBatch(10)).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(attempts()).isEqualTo(1);

        jdbc.sql("UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP").update();
        assertThat(worker.publishBatch(10)).isEqualTo(1);
        assertThat(status()).isEqualTo("PUBLISHED");
        assertThat(worker.publishBatch(10)).isZero();
    }

    @Test
    void repeatedFailuresMoveAnEventToDeadLetterState() {
        insert("evt-dead", "NEW", 0, null);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 2);

        assertThatThrownBy(() -> worker.publishBatch(10)).isInstanceOf(IllegalStateException.class);
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP").update();
        assertThatThrownBy(() -> worker.publishBatch(10)).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("DEAD");
        assertThat(attempts()).isEqualTo(2);
    }

    @Test
    void staleProcessingLeaseCanBeReclaimed() {
        insert("evt-lease", "PROCESSING", 0, "DATEADD('MINUTE', -6, CURRENT_TIMESTAMP)");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThat(worker.publishBatch(10)).isEqualTo(1);
        assertThat(status()).isEqualTo("PUBLISHED");
    }

    @Test
    void processingEventWithoutLeaseCanBeReclaimed() {
        insert("evt-missing-lease", "PROCESSING", 0, null);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThat(worker.publishBatch(10)).isEqualTo(1);
        assertThat(status()).isEqualTo("PUBLISHED");
    }

    @Test
    void lostLeaseIsNotCountedAsPublished() {
        insert("evt-lost-lease", "NEW", 0, null);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            jdbc.sql("UPDATE outbox_event SET locked_by = 'other-worker' WHERE event_id = 'evt-lost-lease'").update();
            return CompletableFuture.completedFuture(null);
        });
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThatThrownBy(() -> worker.publishBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event remains available for retry");
        assertThat(status()).isEqualTo("PROCESSING");
        verify(kafka).send(anyString(), anyString(), anyString());
    }

    @Test
    void laterAggregateEventWaitsUntilItsPredecessorIsPublished() {
        insert("evt-first", "NEW", 0, null, "post-1");
        insert("evt-second", "NEW", 0, null, "post-1");
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(null));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        worker.publishAvailable();

        assertThat(status("evt-first")).isEqualTo("FAILED");
        assertThat(status("evt-second")).isEqualTo("NEW");
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());

        jdbc.sql("UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = 'evt-first'").update();
        worker.publishAvailable();
        assertThat(status("evt-first")).isEqualTo("PUBLISHED");
        assertThat(status("evt-second")).isEqualTo("NEW");

        worker.publishAvailable();
        assertThat(status("evt-second")).isEqualTo("PUBLISHED");
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
    }

    @Test
    void scheduledWorkerTreatsAServiceWithoutAnOutboxTableAsIdle() {
        jdbc.sql("DROP TABLE outbox_event").update();
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThatCode(worker::publishAvailable).doesNotThrowAnyException();
        verifyNoInteractions(kafka);
    }

    @Test
    void operatorReplayReportsAMissingOutboxTable() {
        jdbc.sql("DROP TABLE outbox_event").update();
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThatThrownBy(() -> worker.publishBatch(10))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
        verifyNoInteractions(kafka);
    }

    @Test
    void concurrentScheduledAndOperatorRunsCannotEnterThePublisherTwice() throws Exception {
        insert("evt-concurrent", "NEW", 0, null);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CompletableFuture<SendResult<String, String>> brokerResult = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return brokerResult;
        });
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        CompletableFuture<Integer> activeRun = CompletableFuture.supplyAsync(() -> worker.publishBatch(10));
        assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatCode(worker::publishAvailable).doesNotThrowAnyException();
        assertThatThrownBy(() -> worker.publishBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Outbox publisher is already running");

        brokerResult.complete(null);
        assertThat(activeRun.get(2, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(status()).isEqualTo("PUBLISHED");
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void failureMessageIsTruncatedBeforePersistence() {
        insert("evt-long-error", "NEW", 0, null);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("x".repeat(2_000))));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        assertThatThrownBy(() -> worker.publishBatch(10)).isInstanceOf(IllegalStateException.class);
        Integer length = jdbc.sql("SELECT LENGTH(last_error) FROM outbox_event WHERE event_id = 'evt-long-error'")
                .query(Integer.class).single();
        assertThat(length).isEqualTo(1_000);
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test
    void interruptedKafkaSendKeepsTheEventRetryableAndRestoresInterruptFlag() throws Exception {
        insert("evt-interrupted", "NEW", 0, null);
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("worker interrupted"));
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3);

        try {
            assertThatThrownBy(() -> worker.publishBatch(10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("event remains available for retry");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(status()).isEqualTo("FAILED");
            assertThat(attempts()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void hungKafkaSendTimesOutAndLeavesTheEventRetryable() {
        insert("evt-timeout", "NEW", 0, null);
        CompletableFuture<SendResult<String, String>> hungSend = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(hungSend);
        ReliableOutboxWorker worker = new ReliableOutboxWorker(jdbc, kafka, 10, 3, 20);

        assertThatThrownBy(() -> worker.publishBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event remains available for retry");
        assertThat(status()).isEqualTo("FAILED");
        assertThat(attempts()).isEqualTo(1);
    }

    private void insert(String eventId, String status, int attempts, String lockedAtExpression) {
        insert(eventId, status, attempts, lockedAtExpression, "1");
    }

    private void insert(String eventId, String status, int attempts, String lockedAtExpression, String aggregateId) {
        String locked = lockedAtExpression == null ? "NULL" : lockedAtExpression;
        jdbc.sql("""
                INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload, status, attempts, locked_at)
                VALUES (:eventId, 'test.topic', 'TEST', :aggregateId, '{"eventId":"x"}', :status, :attempts, %s)
                """.formatted(locked))
                .param("eventId", eventId).param("aggregateId", aggregateId)
                .param("status", status).param("attempts", attempts).update();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM outbox_event").query(String.class).single();
    }

    private int attempts() {
        return jdbc.sql("SELECT attempts FROM outbox_event").query(Integer.class).single();
    }

    private String status(String eventId) {
        return jdbc.sql("SELECT status FROM outbox_event WHERE event_id = :eventId")
                .param("eventId", eventId).query(String.class).single();
    }
}
