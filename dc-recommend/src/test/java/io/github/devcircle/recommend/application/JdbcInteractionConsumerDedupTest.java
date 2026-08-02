package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.ValidatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcInteractionConsumerDedupTest {
    private static final String EVENT_ID_1 = "1cbeb009-7673-436f-bb61-563a82ec3df7";
    private static final String EVENT_ID_2 = "2d77fc3b-698d-47ef-9590-9d5e823d47e9";
    private static final String EVENT_ID_3 = "823c4589-494f-4cf0-a639-984f8c61015c";
    private static final String EVENT_ID_4 = "2dd693c8-ea69-493c-9245-06ae3fe0fbb4";
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcClient jdbcClient;
    private JdbcInteractionConsumerDedup dedup;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:recommend-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP TABLE IF EXISTS recommend_consumer_dedup").update();
        jdbcClient.sql("""
                        CREATE TABLE recommend_consumer_dedup (
                            event_id VARCHAR(36) PRIMARY KEY,
                            topic VARCHAR(128) NOT NULL,
                            event_fingerprint CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
                            status VARCHAR(16) NOT NULL,
                            attempts INT NOT NULL DEFAULT 1,
                            lease_token CHAR(36),
                            last_error VARCHAR(1000),
                            processed_at TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """).update();
        dedup = new JdbcInteractionConsumerDedup(jdbcClient);
    }

    @Test
    void failedEventCanBeReclaimedAndCompletedEventCannot() {
        ValidatedEvent event = interactionEvent(EVENT_ID_1, "LIKE");
        var firstClaim = dedup.claim(event);
        assertThat(firstClaim.claimed()).isTrue();
        dedup.markFailed(EVENT_ID_1, firstClaim.leaseToken(), "redis unavailable");
        var retryClaim = dedup.claim(event);
        assertThat(retryClaim.claimed()).isTrue();
        dedup.markCompleted(EVENT_ID_1, retryClaim.leaseToken());

        assertThat(dedup.claim(event).claimed()).isFalse();
        assertPayloadConflict(interactionEvent(EVENT_ID_1, "FAVORITE"));
        assertThat(attempts(EVENT_ID_1)).isEqualTo(2);
    }

    @Test
    void eventIdCannotBeReusedForAnotherTopic() {
        assertThat(dedup.claim(interactionEvent(EVENT_ID_2, "LIKE")).claimed()).isTrue();

        assertThatThrownBy(() -> dedup.claim(followEvent(EVENT_ID_2, "FOLLOWED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interaction.changed");
        assertThat(attempts(EVENT_ID_2)).isEqualTo(1);
    }

    @Test
    void activeProcessingEventIsRetriedInsteadOfSilentlyAcknowledged() {
        ValidatedEvent event = interactionEvent(EVENT_ID_2, "LIKE");
        assertThat(dedup.claim(event).claimed()).isTrue();

        assertThatThrownBy(() -> dedup.claim(event))
                .isInstanceOf(JdbcInteractionConsumerDedup.EventProcessingInProgressException.class)
                .hasMessage("Event " + EVENT_ID_2 + " is already being processed");
        assertThat(attempts(EVENT_ID_2)).isEqualTo(1);
        assertThat(status(EVENT_ID_2)).isEqualTo("PROCESSING");
    }

    @Test
    void failedEventCannotBeReclaimedWithChangedEnvelopeData() {
        Instant occurredAt = Instant.parse("2026-08-01T10:15:30Z");
        ValidatedEvent original = event(EVENT_ID_3, "interaction.changed", occurredAt,
                "dc-interaction", "9001", interactionPayload("LIKE"));
        var claim = dedup.claim(original);
        assertThat(claim.claimed()).isTrue();
        dedup.markFailed(EVENT_ID_3, claim.leaseToken(), "redis unavailable");

        assertPayloadConflict(event(EVENT_ID_3, "interaction.changed", occurredAt.plusSeconds(1),
                "dc-interaction", "9001", interactionPayload("LIKE")));
        assertPayloadConflict(event(EVENT_ID_3, "interaction.changed", occurredAt,
                "another-producer", "9001", interactionPayload("LIKE")));
        assertPayloadConflict(event(EVENT_ID_3, "interaction.changed", occurredAt,
                "dc-interaction", "another-aggregate", interactionPayload("LIKE")));
        assertPayloadConflict(event(EVENT_ID_3, "interaction.changed", occurredAt,
                "dc-interaction", "9001", interactionPayload("FAVORITE")));
        assertThat(attempts(EVENT_ID_3)).isEqualTo(1);
        assertThat(status(EVENT_ID_3)).isEqualTo("FAILED");
    }

    @Test
    void expiredProcessingEventCanOnlyBeRecoveredWithItsOriginalPayload() {
        ValidatedEvent original = interactionEvent(EVENT_ID_4, "LIKE");
        assertThat(dedup.claim(original).claimed()).isTrue();
        jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET updated_at = TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP)
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID_4)
                .update();

        assertPayloadConflict(interactionEvent(EVENT_ID_4, "FAVORITE"));
        assertThat(dedup.claim(original).claimed()).isTrue();
        assertThat(attempts(EVENT_ID_4)).isEqualTo(2);
    }

    @Test
    void legacyFailedRecordIsBoundOnItsFirstRetry() {
        ValidatedEvent original = interactionEvent(EVENT_ID_4, "LIKE");
        var claim = dedup.claim(original);
        assertThat(claim.claimed()).isTrue();
        dedup.markFailed(EVENT_ID_4, claim.leaseToken(), "redis unavailable");
        jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET event_fingerprint = :legacy
                        WHERE event_id = :eventId
                        """)
                .param("legacy", LEGACY_FINGERPRINT)
                .param("eventId", EVENT_ID_4)
                .update();

        ValidatedEvent compatibleRetry = interactionEvent(EVENT_ID_4, "FAVORITE");
        assertThat(dedup.claim(compatibleRetry).claimed()).isTrue();
        assertThat(fingerprint(EVENT_ID_4)).isEqualTo(EventFingerprint.sha256(compatibleRetry));
        assertPayloadConflict(original);
    }

    @Test
    void staleWorkerCannotCompleteOrFailAReclaimedEvent() {
        ValidatedEvent event = interactionEvent(EVENT_ID_4, "LIKE");
        var staleClaim = dedup.claim(event);
        jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET updated_at = TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP)
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID_4)
                .update();
        var activeClaim = dedup.claim(event);

        assertThatThrownBy(() -> dedup.markCompleted(EVENT_ID_4, staleClaim.leaseToken()))
                .isInstanceOf(JdbcInteractionConsumerDedup.EventLeaseLostException.class)
                .hasMessage("Event " + EVENT_ID_4 + " lease was lost before completion");
        assertThatThrownBy(() -> dedup.markFailed(EVENT_ID_4, staleClaim.leaseToken(), "late failure"))
                .isInstanceOf(JdbcInteractionConsumerDedup.EventLeaseLostException.class)
                .hasMessage("Event " + EVENT_ID_4 + " lease was lost before failure recording");
        assertThat(status(EVENT_ID_4)).isEqualTo("PROCESSING");

        dedup.markCompleted(EVENT_ID_4, activeClaim.leaseToken());
        assertThat(status(EVENT_ID_4)).isEqualTo("COMPLETED");
    }

    private int attempts(String eventId) {
        return jdbcClient.sql("SELECT attempts FROM recommend_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single();
    }

    private String status(String eventId) {
        return jdbcClient.sql("SELECT status FROM recommend_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId).query(String.class).single();
    }

    private String fingerprint(String eventId) {
        return jdbcClient.sql("SELECT event_fingerprint FROM recommend_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId).query(String.class).single();
    }

    private void assertPayloadConflict(ValidatedEvent event) {
        assertThatThrownBy(() -> dedup.claim(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already associated with a different payload");
    }

    private ValidatedEvent interactionEvent(String eventId, String interactionType) {
        return event(eventId, "interaction.changed", Instant.parse("2026-08-01T10:15:30Z"),
                "dc-interaction", "9001", interactionPayload(interactionType));
    }

    private ValidatedEvent followEvent(String eventId, String status) {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("actorId", 2L)
                .put("receiverId", 7L)
                .put("status", status);
        return event(eventId, "follow.changed", Instant.parse("2026-08-01T10:15:30Z"),
                "dc-user", "7", payload);
    }

    private ObjectNode interactionPayload(String interactionType) {
        return objectMapper.createObjectNode()
                .put("actorId", 7L)
                .put("postId", 9001L)
                .put("interactionType", interactionType);
    }

    private ValidatedEvent event(String eventId, String topic, Instant occurredAt,
                                 String producer, String aggregateId, ObjectNode payload) {
        return new ValidatedEvent(UUID.fromString(eventId), topic, 1, occurredAt, producer, aggregateId, payload);
    }
}
