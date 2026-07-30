package io.github.devcircle.search.application;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchEventCoordinatorTest {
    private JdbcClient jdbcClient;
    private SearchEventCoordinator coordinator;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:search-event-state;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP TABLE IF EXISTS search_consumer_dedup").update();
        jdbcClient.sql("DROP TABLE IF EXISTS search_event_watermark").update();
        jdbcClient.sql("""
                CREATE TABLE search_consumer_dedup (
                    event_id CHAR(36) PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    post_id BIGINT NOT NULL,
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
        coordinator = new SearchEventCoordinator(jdbcClient);
    }

    @Test
    void completedAndOlderEventsCannotMutateTheIndexAgain() {
        Instant publishedAt = Instant.parse("2026-07-30T10:00:00Z");
        assertThat(coordinator.claim("11111111-1111-1111-1111-111111111111", "audit.completed", 42L, publishedAt))
                .isTrue();
        coordinator.complete("11111111-1111-1111-1111-111111111111", 42L, publishedAt, "UPSERT");

        assertThat(coordinator.claim("11111111-1111-1111-1111-111111111111", "audit.completed", 42L, publishedAt))
                .isFalse();
        assertThat(coordinator.claim("22222222-2222-2222-2222-222222222222", "content.rejected", 42L,
                publishedAt.minusSeconds(10))).isFalse();

        String status = jdbcClient.sql("SELECT status FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", "22222222-2222-2222-2222-222222222222")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    void failedEventReleasesItsLeaseAndCanBeRetried() {
        String eventId = "33333333-3333-3333-3333-333333333333";
        Instant occurredAt = Instant.parse("2026-07-30T11:00:00Z");
        assertThat(coordinator.claim(eventId, "content.rejected", 73L, occurredAt)).isTrue();

        coordinator.fail(eventId, 73L, new IllegalStateException("elasticsearch unavailable"));

        assertThat(coordinator.claim(eventId, "content.rejected", 73L, occurredAt)).isTrue();
        Integer attempts = jdbcClient.sql("SELECT attempts FROM search_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        assertThat(attempts).isEqualTo(2);
    }
}
