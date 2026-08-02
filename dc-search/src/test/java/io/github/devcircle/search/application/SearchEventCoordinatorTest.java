package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(SearchEventCoordinatorTest.TestConfig.class)
class SearchEventCoordinatorTest {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private SearchEventCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP TABLE IF EXISTS search_consumer_dedup").update();
        jdbcClient.sql("DROP TABLE IF EXISTS search_event_watermark").update();
        jdbcClient.sql("""
                CREATE TABLE search_consumer_dedup (
                    event_id CHAR(36) PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    post_id BIGINT NOT NULL,
                    event_fingerprint CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
                    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
                    attempts INT NOT NULL DEFAULT 1,
                    last_error VARCHAR(1000),
                    processed_at TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();
        jdbcClient.sql("""
                CREATE TABLE search_event_watermark (
                    post_id BIGINT PRIMARY KEY,
                    occurred_at TIMESTAMP,
                    last_event_id CHAR(36),
                    last_action VARCHAR(16),
                    lease_owner CHAR(36),
                    lease_until TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """).update();
    }

    @Test
    void completedAndOlderEventsCannotMutateTheIndexAgain() {
        Instant publishedAt = Instant.parse("2026-07-30T10:00:00Z");
        ValidatedEvent published = event(
                "11111111-1111-1111-1111-111111111111", "audit.completed", 42L, publishedAt);
        assertThat(coordinator.claim(published, 42L))
                .isTrue();
        coordinator.complete("11111111-1111-1111-1111-111111111111", 42L, publishedAt, "UPSERT");

        assertThat(coordinator.claim(published, 42L))
                .isFalse();
        assertPayloadConflict(event(
                published.eventId().toString(), published.type(), 42L, publishedAt,
                published.producer(), published.aggregateId(), payload(42L, "changed")), 42L);
        assertThat(coordinator.claim(event(
                "22222222-2222-2222-2222-222222222222", "content.rejected", 42L,
                publishedAt.minusSeconds(10)), 42L)).isFalse();

        String status = jdbcClient.sql("SELECT status FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", "22222222-2222-2222-2222-222222222222")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("COMPLETED");
        String leaseOwner = jdbcClient.sql(
                        "SELECT COALESCE(lease_owner, '') FROM search_event_watermark WHERE post_id = 42")
                .query(String.class)
                .single();
        assertThat(leaseOwner).isEmpty();
    }

    @Test
    void failedEventReleasesItsLeaseAndCanBeRetried() {
        String eventId = "33333333-3333-3333-3333-333333333333";
        Instant occurredAt = Instant.parse("2026-07-30T11:00:00Z");
        ValidatedEvent event = event(eventId, "content.rejected", 73L, occurredAt);
        assertThat(coordinator.claim(event, 73L)).isTrue();

        coordinator.fail(eventId, 73L, new IllegalStateException("elasticsearch unavailable"));
        String releasedLease = jdbcClient.sql(
                        "SELECT COALESCE(lease_owner, '') FROM search_event_watermark WHERE post_id = 73")
                .query(String.class)
                .single();
        assertThat(releasedLease).isEmpty();

        assertThat(coordinator.claim(event, 73L)).isTrue();
        Integer attempts = jdbcClient.sql("SELECT attempts FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void eventIdCannotBeRetriedAgainstAnotherTopicOrPost() {
        String eventId = "7be56fe6-d3b8-46ce-899a-e633150a5f45";
        Instant occurredAt = Instant.parse("2026-07-30T11:00:00Z");
        assertThat(coordinator.claim(event(eventId, "post.updated", 73L, occurredAt), 73L)).isTrue();
        coordinator.fail(eventId, 73L, new IllegalStateException("elasticsearch unavailable"));

        assertThatThrownBy(() -> coordinator.claim(
                event(eventId, "content.rejected", 73L, occurredAt), 73L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search event ID is already bound to post.updated/73");
        assertThatThrownBy(() -> coordinator.claim(
                event(eventId, "post.updated", 74L, occurredAt), 74L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search event ID is already bound to post.updated/73");
    }

    @Test
    void concurrentEventForTheSamePostIsFailedUntilTheLeaseIsReleased() {
        String first = "1603b780-e1d5-4f3b-b8f6-5ea67626dac1";
        String second = "321d4a42-6a74-4f5f-996e-490d424455db";
        Instant occurredAt = Instant.parse("2026-07-30T12:00:00Z");
        assertThat(coordinator.claim(event(first, "post.updated", 88L, occurredAt), 88L)).isTrue();

        assertThatThrownBy(() -> coordinator.claim(
                event(second, "post.updated", 88L, occurredAt.plusSeconds(1)), 88L))
                .isInstanceOf(SearchEventCoordinator.SearchAggregateBusyException.class)
                .hasMessage("Search event for post 88 is already being processed");

        String status = jdbcClient.sql("SELECT status FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", second).query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
        String leaseOwner = jdbcClient.sql(
                        "SELECT lease_owner FROM search_event_watermark WHERE post_id = 88")
                .query(String.class)
                .single();
        assertThat(leaseOwner).isEqualTo(first);
    }

    @Test
    void completionWithoutAnOwnedLeaseIsRejected() {
        assertThatThrownBy(() -> coordinator.complete(
                "8a650a91-f9d0-47d8-8828-9d21545e7a83", 89L,
                Instant.parse("2026-07-30T12:00:00Z"), "UPSERT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search event lease was lost before completion");
    }

    @Test
    void failureWithoutADiagnosticMessageStillPersistsASafeReason() {
        String eventId = "4194f31d-44ea-4dcb-a935-f4eeabcb2a17";
        Instant occurredAt = Instant.parse("2026-07-30T12:00:00Z");
        assertThat(coordinator.claim(event(eventId, "post.updated", 90L, occurredAt), 90L)).isTrue();

        coordinator.fail(eventId, 90L, null);

        String error = jdbcClient.sql("SELECT last_error FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId).query(String.class).single();
        assertThat(error).isEqualTo("unknown search indexing failure");
    }

    @Test
    void failedEventCannotBeRetriedWithChangedEnvelopeData() {
        String eventId = "5a04f9e7-caf6-4e3b-a834-f5fca6f9b560";
        Instant occurredAt = Instant.parse("2026-07-30T13:00:00Z");
        ValidatedEvent original = event(eventId, "post.updated", 95L, occurredAt);
        assertThat(coordinator.claim(original, 95L)).isTrue();
        coordinator.fail(eventId, 95L, new IllegalStateException("elasticsearch unavailable"));

        assertPayloadConflict(event(eventId, "post.updated", 95L, occurredAt.plusSeconds(1)), 95L);
        assertPayloadConflict(event(eventId, "post.updated", 95L, occurredAt,
                "another-producer", "95", payload(95L, "original")), 95L);
        assertPayloadConflict(event(eventId, "post.updated", 95L, occurredAt,
                "dc-content", "another-aggregate", payload(95L, "original")), 95L);
        assertPayloadConflict(event(eventId, "post.updated", 95L, occurredAt,
                "dc-content", "95", payload(95L, "changed")), 95L);

        Integer attempts = jdbcClient.sql(
                        "SELECT attempts FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void expiredProcessingEventCanOnlyBeRecoveredWithItsOriginalPayload() {
        String eventId = "8ac7444c-e86c-4d49-9a96-51c870db8c3b";
        Instant occurredAt = Instant.parse("2026-07-30T14:00:00Z");
        ValidatedEvent original = event(eventId, "post.updated", 96L, occurredAt);
        assertThat(coordinator.claim(original, 96L)).isTrue();
        jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET updated_at = TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP)
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .update();

        assertPayloadConflict(event(eventId, "post.updated", 96L, occurredAt,
                "dc-content", "96", payload(96L, "changed")), 96L);
        assertThat(coordinator.claim(original, 96L)).isTrue();

        Integer attempts = jdbcClient.sql(
                        "SELECT attempts FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    void legacyFingerprintIsUpgradedWhenAFailedEventIsRetried() {
        String eventId = "dfe34bbc-e18f-487c-ac9d-cf1cc8438975";
        Instant occurredAt = Instant.parse("2026-07-30T15:00:00Z");
        ValidatedEvent original = event(eventId, "post.updated", 97L, occurredAt);
        assertThat(coordinator.claim(original, 97L)).isTrue();
        coordinator.fail(eventId, 97L, new IllegalStateException("elasticsearch unavailable"));
        jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET event_fingerprint = :legacy
                        WHERE event_id = :eventId
                        """)
                .param("legacy", LEGACY_FINGERPRINT)
                .param("eventId", eventId)
                .update();

        ValidatedEvent compatibleRetry = event(eventId, "post.updated", 97L, occurredAt,
                "dc-content", "97", payload(97L, "legacy-retry"));
        assertThat(coordinator.claim(compatibleRetry, 97L)).isTrue();

        String fingerprint = jdbcClient.sql(
                        "SELECT event_fingerprint FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
        assertThat(fingerprint).isEqualTo(EventFingerprint.sha256(compatibleRetry));
        assertPayloadConflict(original, 97L);
    }

    private void assertPayloadConflict(ValidatedEvent event, long postId) {
        assertThatThrownBy(() -> coordinator.claim(event, postId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search event ID is already bound to a different payload");
    }

    private ValidatedEvent event(String eventId, String topic, long postId, Instant occurredAt) {
        String producer = topic.startsWith("content.") || "audit.completed".equals(topic)
                ? "dc-admin"
                : "dc-content";
        return event(eventId, topic, postId, occurredAt, producer, Long.toString(postId), payload(postId, "original"));
    }

    private ValidatedEvent event(String eventId, String topic, long postId, Instant occurredAt,
                                 String producer, String aggregateId, ObjectNode payload) {
        return new ValidatedEvent(UUID.fromString(eventId), topic, 1, occurredAt, producer, aggregateId, payload);
    }

    private ObjectNode payload(long postId, String title) {
        return objectMapper.createObjectNode().put("postId", postId).put("title", title);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:search-event-state;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        SearchEventCoordinator searchEventCoordinator(JdbcClient jdbcClient) {
            return new SearchEventCoordinator(jdbcClient);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
