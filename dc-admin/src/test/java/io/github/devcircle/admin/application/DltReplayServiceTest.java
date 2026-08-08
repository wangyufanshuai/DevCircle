package io.github.devcircle.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.DltReplayRequest;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DltReplayServiceTest {
    private JdbcClient jdbcClient;
    private DltRecordReader recordReader;
    private KafkaTemplate<Object, Object> kafkaTemplate;
    private DltReplayService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dlt-replay;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE kafka_dlt_replay (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
                    request_fingerprint CHAR(64) NOT NULL,
                    source_topic VARCHAR(128) NOT NULL,
                    dlt_topic VARCHAR(132) NOT NULL,
                    dlt_partition INT NOT NULL,
                    dlt_offset BIGINT NOT NULL,
                    event_id CHAR(36) NOT NULL,
                    actor_id BIGINT NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    UNIQUE(dlt_topic, dlt_partition, dlt_offset)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT,
                    action VARCHAR(96) NOT NULL,
                    target_type VARCHAR(132) NOT NULL,
                    target_id VARCHAR(96),
                    trace_id VARCHAR(128),
                    metadata JSON,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
                """);
        jdbcClient = JdbcClient.create(dataSource);
        recordReader = mock(DltRecordReader.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        service = new DltReplayService(jdbcClient, new ObjectMapper().findAndRegisterModules(), recordReader,
                kafkaTemplate, new DataSourceTransactionManager(dataSource), 1000);
    }

    @Test
    void successfulReplayIsAuditedAndSameKeyDoesNotRepublish() {
        DltReplayRequest request = request(3, "Elasticsearch recovered; replay the failed projection.");
        DltRecordReader.DltRecord record = record(3, event("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        when(recordReader.read("audit.completed", 0, 3)).thenReturn(record);
        when(kafkaTemplate.send("audit.completed", "post-7", record.value()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var first = service.replay(request, 9L, "trace-1", "replay-key");
        var duplicate = service.replay(request, 9L, "trace-1", "replay-key");

        assertThat(first.attributes()).containsEntry("duplicateRequest", false)
                .containsEntry("eventId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(duplicate.attributes()).containsEntry("duplicateRequest", true);
        verify(kafkaTemplate, times(1)).send("audit.completed", "post-7", record.value());
        assertThat(count("kafka_dlt_replay")).isEqualTo(1);
        assertThat(auditCount("REPLAY_KAFKA_DLT")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyAndDltLocationCannotBeRebound() {
        DltReplayRequest first = request(3, "Replay after dependency recovery.");
        DltRecordReader.DltRecord firstRecord = record(3, event("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        when(recordReader.read("audit.completed", 0, 3)).thenReturn(firstRecord);
        when(kafkaTemplate.send("audit.completed", "post-7", firstRecord.value()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        service.replay(first, 9L, "trace", "bound-key");

        DltReplayRequest changed = request(4, "Replay a different dead-letter record.");
        when(recordReader.read("audit.completed", 0, 4))
                .thenReturn(record(4, event("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")));
        assertCode(() -> service.replay(changed, 9L, "trace", "bound-key"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.replay(first, 9L, "trace", "different-key"), ErrorCode.DUPLICATE_OPERATION);
        verify(kafkaTemplate, times(1)).send("audit.completed", "post-7", firstRecord.value());
    }

    @Test
    void failedPublishRollsBackReplayClaimButKeepsFailureAuditAndCanRetry() {
        DltReplayRequest request = request(5, "Retry after Kafka producer recovery.");
        DltRecordReader.DltRecord record = record(5, event("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        when(recordReader.read("audit.completed", 0, 5)).thenReturn(record);
        CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send("audit.completed", "post-7", record.value())).thenReturn(failed);

        assertCode(() -> service.replay(request, 9L, "trace-failed", "retry-key"),
                ErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(count("kafka_dlt_replay")).isZero();
        assertThat(auditCount("REPLAY_KAFKA_DLT_FAILED")).isEqualTo(1);

        when(kafkaTemplate.send("audit.completed", "post-7", record.value()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var recovered = service.replay(request, 9L, "trace-recovered", "retry-key");

        assertThat(recovered.attributes()).containsEntry("duplicateRequest", false);
        assertThat(count("kafka_dlt_replay")).isEqualTo(1);
        assertThat(auditCount("REPLAY_KAFKA_DLT")).isEqualTo(1);
    }

    @Test
    void mismatchedOriginalTopicAndUnsupportedTopicsAreRejectedBeforePublish() {
        DltReplayRequest request = request(6, "Replay only the expected source topic.");
        DltRecordReader.DltRecord mismatched = new DltRecordReader.DltRecord(
                "audit.completed.DLT", 0, 6, "content.rejected", "post-7",
                event("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        when(recordReader.read("audit.completed", 0, 6)).thenReturn(mismatched);

        assertCode(() -> service.replay(request, 9L, "trace", "mismatch-key"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.replay(new DltReplayRequest("arbitrary.topic", 0, 0,
                        "Do not permit arbitrary broker writes."), 9L, "trace", "arbitrary-key"),
                ErrorCode.VALIDATION_FAILED);
        verify(kafkaTemplate, times(0)).send(eq("audit.completed"), eq("post-7"), eq(mismatched.value()));
    }

    private DltReplayRequest request(long offset, String reason) {
        return new DltReplayRequest("audit.completed", 0, offset, reason);
    }

    private DltRecordReader.DltRecord record(long offset, String value) {
        return new DltRecordReader.DltRecord("audit.completed.DLT", 0, offset,
                "audit.completed", "post-7", value);
    }

    private String event(String eventId) {
        return """
                {"eventId":"%s","type":"audit.completed","version":1,
                 "occurredAt":"2026-08-08T12:00:00Z","producer":"dc-admin","aggregateId":"7",
                 "payload":{"postId":7,"status":"PUBLISHED"}}
                """.formatted(eventId);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long auditCount(String action) {
        return jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE action = :action")
                .param("action", action).query(Long.class).single();
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
