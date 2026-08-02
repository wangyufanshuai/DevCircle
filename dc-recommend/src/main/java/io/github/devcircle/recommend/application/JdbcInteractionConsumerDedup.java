package io.github.devcircle.recommend.application;

import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
class JdbcInteractionConsumerDedup implements InteractionConsumerDedup {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final JdbcClient jdbcClient;

    JdbcInteractionConsumerDedup(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public ClaimResult claim(ValidatedEvent event) {
        String eventId = event.eventId().toString();
        String topic = event.type();
        String eventFingerprint = EventFingerprint.sha256(event);
        String leaseToken = UUID.randomUUID().toString();
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO recommend_consumer_dedup(
                            event_id, topic, event_fingerprint, status, attempts, lease_token)
                        VALUES (:eventId, :topic, :eventFingerprint, 'PROCESSING', 1, :leaseToken)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("eventFingerprint", eventFingerprint)
                .param("leaseToken", leaseToken)
                .update();
        if (inserted == 1) return ClaimResult.acquired(leaseToken);

        validateIdentity(loadRecord(eventId), topic, eventFingerprint);

        int reclaimed = jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'PROCESSING', attempts = attempts + 1, last_error = NULL,
                            event_fingerprint = :eventFingerprint, lease_token = :leaseToken
                        WHERE event_id = :eventId
                          AND topic = :topic
                          AND event_fingerprint IN (:eventFingerprint, :legacyFingerprint)
                          AND (status = 'FAILED'
                            OR (status = 'PROCESSING'
                              AND updated_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP)))
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("eventFingerprint", eventFingerprint)
                .param("legacyFingerprint", LEGACY_FINGERPRINT)
                .param("leaseToken", leaseToken)
                .update();
        if (reclaimed == 1) return ClaimResult.acquired(leaseToken);
        DedupRecord current = loadRecord(eventId);
        validateIdentity(current, topic, eventFingerprint);
        if ("COMPLETED".equals(current.status())) return ClaimResult.duplicate();
        if ("PROCESSING".equals(current.status())) {
            throw new EventProcessingInProgressException(
                    "Event " + eventId + " is already being processed");
        }
        throw new IllegalStateException("Unsupported recommendation consumer state: " + current.status());
    }

    private DedupRecord loadRecord(String eventId) {
        return jdbcClient.sql("""
                        SELECT topic, event_fingerprint, status
                        FROM recommend_consumer_dedup
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new DedupRecord(
                        rs.getString("topic"),
                        rs.getString("event_fingerprint"),
                        rs.getString("status")))
                .single();
    }

    private void validateIdentity(DedupRecord existing, String topic, String eventFingerprint) {
        if (!existing.topic().equals(topic)) {
            throw new IllegalArgumentException("Event ID is already associated with topic " + existing.topic());
        }
        if (!LEGACY_FINGERPRINT.equals(existing.eventFingerprint())
                && !eventFingerprint.equals(existing.eventFingerprint())) {
            throw new IllegalArgumentException("Event ID is already associated with a different payload");
        }
    }

    @Override
    public void markCompleted(String eventId, String leaseToken) {
        int updated = jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP, last_error = NULL,
                            lease_token = NULL
                        WHERE event_id = :eventId AND status = 'PROCESSING' AND lease_token = :leaseToken
                        """)
                .param("eventId", eventId).param("leaseToken", leaseToken).update();
        if (updated != 1) {
            throw new EventLeaseLostException("Event " + eventId + " lease was lost before completion");
        }
    }

    @Override
    public void markFailed(String eventId, String leaseToken, String message) {
        String safeMessage = message == null ? "unknown failure" : message;
        int updated = jdbcClient.sql("""
                        UPDATE recommend_consumer_dedup
                        SET status = 'FAILED', last_error = :lastError, lease_token = NULL
                        WHERE event_id = :eventId AND status = 'PROCESSING' AND lease_token = :leaseToken
                        """)
                .param("eventId", eventId)
                .param("leaseToken", leaseToken)
                .param("lastError", safeMessage.substring(0, Math.min(1000, safeMessage.length())))
                .update();
        if (updated != 1) {
            throw new EventLeaseLostException("Event " + eventId + " lease was lost before failure recording");
        }
    }

    private record DedupRecord(String topic, String eventFingerprint, String status) {
    }

    static final class EventProcessingInProgressException extends IllegalStateException {
        EventProcessingInProgressException(String message) {
            super(message);
        }
    }

    static final class EventLeaseLostException extends IllegalStateException {
        EventLeaseLostException(String message) {
            super(message);
        }
    }
}
