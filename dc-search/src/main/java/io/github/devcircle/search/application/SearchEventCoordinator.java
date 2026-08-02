package io.github.devcircle.search.application;

import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
class SearchEventCoordinator {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final JdbcClient jdbcClient;

    SearchEventCoordinator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(noRollbackFor = SearchAggregateBusyException.class)
    public ClaimResult claim(ValidatedEvent event, long postId) {
        String eventId = event.eventId().toString();
        String topic = event.type();
        Instant occurredAt = event.occurredAt();
        String eventFingerprint = EventFingerprint.sha256(event);
        String leaseToken = UUID.randomUUID().toString();
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO search_consumer_dedup(
                            event_id, topic, post_id, event_fingerprint, status, attempts, lease_token)
                        VALUES (:eventId, :topic, :postId, :eventFingerprint, 'PROCESSING', 1, :leaseToken)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("postId", postId)
                .param("eventFingerprint", eventFingerprint)
                .param("leaseToken", leaseToken)
                .update();
        if (inserted == 0) {
            validateIdentity(loadIdentity(eventId), topic, postId, eventFingerprint);
            int reclaimed = jdbcClient.sql("""
                            UPDATE search_consumer_dedup
                            SET status = 'PROCESSING', attempts = attempts + 1, last_error = NULL,
                                event_fingerprint = :eventFingerprint, lease_token = :leaseToken
                            WHERE event_id = :eventId
                              AND event_fingerprint IN (:eventFingerprint, :legacyFingerprint)
                              AND (status = 'FAILED'
                                OR (status = 'PROCESSING' AND updated_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP)))
                            """)
                    .param("eventId", eventId)
                    .param("eventFingerprint", eventFingerprint)
                    .param("legacyFingerprint", LEGACY_FINGERPRINT)
                    .param("leaseToken", leaseToken)
                    .update();
            if (reclaimed == 0) {
                validateIdentity(loadIdentity(eventId), topic, postId, eventFingerprint);
                return ClaimResult.duplicate();
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
                        SET lease_owner = :leaseToken,
                            lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
                        WHERE post_id = :postId
                          AND (lease_owner IS NULL OR lease_owner = :leaseToken
                            OR lease_until < CURRENT_TIMESTAMP)
                        """)
                .param("leaseToken", leaseToken)
                .param("postId", postId)
                .update();
        if (leased == 0) {
            markFailedRecord(eventId, leaseToken, "search aggregate is busy");
            throw new SearchAggregateBusyException(
                    "Search event for post " + postId + " is already being processed");
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
            markCompletedRecord(eventId, leaseToken);
            releaseLease(leaseToken, postId);
            return ClaimResult.duplicate();
        }
        return ClaimResult.acquired(leaseToken);
    }

    private EventIdentity loadIdentity(String eventId) {
        return jdbcClient.sql("""
                        SELECT topic, post_id, event_fingerprint
                        FROM search_consumer_dedup
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new EventIdentity(
                        rs.getString("topic"),
                        rs.getLong("post_id"),
                        rs.getString("event_fingerprint")))
                .single();
    }

    private void validateIdentity(EventIdentity identity, String topic, long postId, String eventFingerprint) {
        if (!topic.equals(identity.topic()) || postId != identity.postId()) {
            throw new IllegalArgumentException("Search event ID is already bound to "
                    + identity.topic() + "/" + identity.postId());
        }
        if (!LEGACY_FINGERPRINT.equals(identity.eventFingerprint())
                && !eventFingerprint.equals(identity.eventFingerprint())) {
            throw new IllegalArgumentException("Search event ID is already bound to a different payload");
        }
    }

    @Transactional
    public void complete(String eventId, long postId, Instant occurredAt, String action, String leaseToken) {
        int updated = jdbcClient.sql("""
                        UPDATE search_event_watermark
                        SET occurred_at = :occurredAt,
                            last_event_id = :eventId,
                            last_action = :action,
                            lease_owner = NULL,
                            lease_until = NULL
                        WHERE post_id = :postId AND lease_owner = :leaseToken
                        """)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("eventId", eventId)
                .param("action", action)
                .param("postId", postId)
                .param("leaseToken", leaseToken)
                .update();
        if (updated != 1) {
            throw new SearchEventLeaseLostException("Search event lease was lost before completion");
        }
        markCompletedRecord(eventId, leaseToken);
    }

    @Transactional
    public void fail(String eventId, long postId, Throwable failure, String leaseToken) {
        String message = failure == null || failure.getMessage() == null
                ? "unknown search indexing failure"
                : failure.getMessage();
        markFailedRecord(eventId, leaseToken, message);
        releaseLease(leaseToken, postId);
    }

    private void markCompletedRecord(String eventId, String leaseToken) {
        int updated = jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP, last_error = NULL,
                            lease_token = NULL
                        WHERE event_id = :eventId AND status = 'PROCESSING' AND lease_token = :leaseToken
                        """)
                .param("eventId", eventId)
                .param("leaseToken", leaseToken)
                .update();
        if (updated != 1) {
            throw new SearchEventLeaseLostException(
                    "Search event lease was lost before recording completion");
        }
    }

    private void markFailedRecord(String eventId, String leaseToken, String message) {
        String safeMessage = message.substring(0, Math.min(1000, message.length()));
        int updated = jdbcClient.sql("""
                        UPDATE search_consumer_dedup
                        SET status = 'FAILED', last_error = :lastError, lease_token = NULL
                        WHERE event_id = :eventId AND status = 'PROCESSING' AND lease_token = :leaseToken
                        """)
                .param("eventId", eventId)
                .param("leaseToken", leaseToken)
                .param("lastError", safeMessage)
                .update();
        if (updated != 1) {
            throw new SearchEventLeaseLostException(
                    "Search event lease was lost before recording failure");
        }
    }

    private void releaseLease(String leaseToken, long postId) {
        int updated = jdbcClient.sql("""
                        UPDATE search_event_watermark
                        SET lease_owner = NULL, lease_until = NULL
                        WHERE post_id = :postId AND lease_owner = :leaseToken
                        """)
                .param("postId", postId)
                .param("leaseToken", leaseToken)
                .update();
        if (updated != 1) {
            throw new SearchEventLeaseLostException(
                    "Search event lease was lost before release");
        }
    }

    private record EventWatermark(Instant occurredAt) {
    }

    private record EventIdentity(String topic, long postId, String eventFingerprint) {
    }

    record ClaimResult(boolean claimed, String leaseToken) {
        static ClaimResult acquired(String leaseToken) {
            return new ClaimResult(true, leaseToken);
        }

        static ClaimResult duplicate() {
            return new ClaimResult(false, null);
        }
    }

    static final class SearchAggregateBusyException extends IllegalStateException {
        SearchAggregateBusyException(String message) {
            super(message);
        }
    }

    static final class SearchEventLeaseLostException extends IllegalStateException {
        SearchEventLeaseLostException(String message) {
            super(message);
        }
    }
}
