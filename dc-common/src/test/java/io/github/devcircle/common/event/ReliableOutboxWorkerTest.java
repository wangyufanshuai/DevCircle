package io.github.devcircle.common.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

    private void insert(String eventId, String status, int attempts, String lockedAtExpression) {
        String locked = lockedAtExpression == null ? "NULL" : lockedAtExpression;
        jdbc.sql("""
                INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload, status, attempts, locked_at)
                VALUES (:eventId, 'test.topic', 'TEST', '1', '{"eventId":"x"}', :status, :attempts, %s)
                """.formatted(locked))
                .param("eventId", eventId).param("status", status).param("attempts", attempts).update();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM outbox_event").query(String.class).single();
    }

    private int attempts() {
        return jdbc.sql("SELECT attempts FROM outbox_event").query(Integer.class).single();
    }
}
