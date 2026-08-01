package io.github.devcircle.search.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
class SearchEventCoordinator {
    private final JdbcClient jdbcClient;

    SearchEventCoordinator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public boolean claim(String eventId, String topic, long postId, Instant occurredAt) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO search_consumer_dedup(event_id, topic, post_id, status, attempts)
                        VALUES (:eventId, :topic, :postId, 'PROCESSING', 1)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("postId", postId)
                .update();
        if (inserted == 0) {
            EventIdentity identity = jdbcClient.sql("""
                            SELECT topic, post_id
                            FROM search_consumer_dedup
                            WHERE event_id = :eventId
                            """)
                    .param("eventId", eventId)
                    .query((rs, rowNum) -> new EventIdentity(rs.getString("topic"), rs.getLong("post_id")))
                    .single();
            if (!topic.equals(identity.topic()) || postId != identity.postId()) {
                throw new IllegalArgumentException("Search event ID is already bound to "
                        + identity.topic() + "/" + identity.postId());
            }
            int reclaimed = jdbcClient.sql("""
                            UPDATE search_consumer_dedup
                            SET status = 'PROCESSING', attempts = attempts + 1, last_error = NULL
                            WHERE event_id = :eventId
                              AND (status = 'FAILED'
                                OR (status = 'PROCESSING' AND updated_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP)))
                            """)
                    .param("eventId", eventId)
                    .update();
            if (reclaimed == 0) {
                return false;
            }
        }

        jdbcClient.sql("""
                        INSERT IGNORE INTO search_event_watermark(post_id)
                        VALUES (:postId)
                        """)
                .param("postId", postId)
                .update();
        int leased = jdbcClient.sql("""
                        UPDATE search_event_watermark
                        SET lease_owner = :eventId,
                            lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
                        WHERE post_id = :postId
                          AND (lease_owner IS NULL OR lease_owner = :eventId
                            OR lease_until < CURRENT_TIMESTAMP)
                        """)
                .param("eventId", eventId)
                .param("postId", postId)
                .update();
        if (leased == 0) {
            markFailedRecord(eventId, "search aggregate is busy");
            throw new IllegalStateException("Search event for post " + postId + " is already being processed");
        }

        EventWatermark watermark = jdbcClient.sql("""
                        SELECT occurred_at FROM search_event_watermark WHERE post_id = :postId
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> {
                    Timestamp value = rs.getTimestamp("occurred_at");
                    return new EventWatermark(value == null ? null : value.toInstant());
                })
                .single();
        if (watermark.occurredAt() != null && !watermark.occurredAt().isBefore(occurredAt)) {
            markCompletedRecord(eventId);
            releaseLease(eventId, postId);
            return false;
        }
        return true;
    }

    @Transactional
    public void complete(String eventId, long postId, Instant occurredAt, String action) {
        int updated = jdbcClient.sql("""
                        UPDATE search_event_watermark
                        SET occurred_at = :occurredAt,
                            last_event_id = :eventId,
                            last_action = :action,
                            lease_owner = NULL,
                            lease_until = NULL
                        WHERE post_id = :postId AND lease_owner = :eventId
                        """)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("eventId", eventId)
                .param("action", action)
                .param("postId", postId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Search event lease was lost before completion");
        }
        markCompletedRecord(eventId);
    }

    @Transactional
    public void fail(String eventId, long postId, Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "unknown search indexing failure"
                : failure.getMessage();
        markFailedRecord(eventId, message);
        releaseLease(eventId, postId);
    }

    private void markCompletedRecord(String eventId) {
        jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP, last_error = NULL
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .update();
    }

    private void markFailedRecord(String eventId, String message) {
        String safeMessage = message.substring(0, Math.min(1000, message.length()));
        jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET status = 'FAILED', last_error = :lastError
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .param("lastError", safeMessage)
                .update();
    }

    private void releaseLease(String eventId, long postId) {
        jdbcClient.sql("""
                        UPDATE search_event_watermark
                        SET lease_owner = NULL, lease_until = NULL
                        WHERE post_id = :postId AND lease_owner = :eventId
                        """)
                .param("postId", postId)
                .param("eventId", eventId)
                .update();
    }

    private record EventWatermark(Instant occurredAt) {
    }

    private record EventIdentity(String topic, long postId) {
    }
}
