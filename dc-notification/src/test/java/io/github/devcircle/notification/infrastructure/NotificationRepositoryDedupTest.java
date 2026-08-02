package io.github.devcircle.notification.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRepositoryDedupTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private JdbcClient jdbcClient;
    private NotificationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:notification-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE consumer_dedup (
                    event_id CHAR(36) PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    event_fingerprint CHAR(64) NOT NULL,
                    consumed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE notification (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    receiver_id BIGINT NOT NULL,
                    actor_id BIGINT NULL,
                    type VARCHAR(64) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    content VARCHAR(512) NOT NULL,
                    target_type VARCHAR(32) NULL,
                    target_id BIGINT NULL,
                    target_route VARCHAR(255) NULL,
                    read_flag BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """).update();
        repository = new NotificationRepository(jdbcClient);
    }

    @Test
    void duplicatePayloadIsIdempotentButEventIdReuseIsRejected() {
        String eventId = "f3887f15-5f26-45e5-86ee-8657eea6ac6f";
        ValidatedEvent original = event(eventId, EventTopics.INTERACTION_CHANGED,
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "LIKE"));
        ValidatedEvent changedPayload = event(eventId, EventTopics.INTERACTION_CHANGED,
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "FAVORITE"));
        ValidatedEvent changedTopic = event(eventId, EventTopics.FOLLOW_CHANGED,
                Map.of("actorId", 2L, "receiverId", 7L, "status", "FOLLOWED"));

        assertThat(repository.createOnce(original, 7L, 2L,
                "INTERACTION", "title", "content", "POST", 9001L, "/posts/9001")).isTrue();
        assertThat(repository.createOnce(original, 7L, 2L,
                "INTERACTION", "title", "content", "POST", 9001L, "/posts/9001")).isFalse();

        assertThatThrownBy(() -> repository.createOnce(changedPayload, 7L, 2L,
                "INTERACTION", "changed", "changed", "POST", 9001L, "/posts/9001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to a different topic or payload");

        assertThatThrownBy(() -> repository.acknowledgeOnce(changedTopic))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to a different topic or payload");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM notification").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void legacyFingerprintAllowsOneVersionOfSameTopicReplay() {
        String eventId = "f3887f15-5f26-45e5-86ee-8657eea6ac70";
        ValidatedEvent original = event(eventId, EventTopics.INTERACTION_CHANGED, Map.of("status", "LIKE"));
        ValidatedEvent changedPayload = event(eventId, EventTopics.INTERACTION_CHANGED,
                Map.of("status", "FAVORITE"));
        repository.createOnce(original, 7L, 2L,
                "INTERACTION", "title", "content", "POST", 9001L, "/posts/9001");
        jdbcClient.sql("""
                        UPDATE consumer_dedup SET event_fingerprint = :legacy WHERE event_id = :eventId
                        """)
                .param("legacy", "0".repeat(64)).param("eventId", eventId).update();

        assertThat(repository.createOnce(changedPayload, 7L, 2L,
                "INTERACTION", "changed", "changed", "POST", 9001L, "/posts/9001")).isFalse();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM notification").query(Long.class).single()).isEqualTo(1L);
    }

    private ValidatedEvent event(String eventId, String topic, Map<String, Object> payload) {
        return new ValidatedEvent(UUID.fromString(eventId), topic, 1, OCCURRED_AT, "test-producer", "7",
                OBJECT_MAPPER.valueToTree(payload));
    }
}
