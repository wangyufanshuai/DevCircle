package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentInteractionProjectionRepository {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final JdbcClient jdbcClient;
    private final ContentCounterProjectionCoordinator counterCoordinator;

    public ContentInteractionProjectionRepository(JdbcClient jdbcClient,
                                                  ContentCounterProjectionCoordinator counterCoordinator) {
        this.jdbcClient = jdbcClient;
        this.counterCoordinator = counterCoordinator;
    }

    @Transactional
    public boolean apply(ValidatedEvent event, Long postId, String interactionType) {
        return apply(event, postId, interactionType, null, null);
    }

    @Transactional
    public boolean apply(ValidatedEvent event, Long postId, String interactionType,
                         Long sourceVersion, Long absoluteLikeCount) {
        String eventId = event.eventId().toString();
        String topic = event.type();
        String eventFingerprint = EventFingerprint.sha256(event);
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO interaction_projection_dedup(event_id, topic, event_fingerprint)
                        VALUES (:eventId, :topic, :eventFingerprint)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("eventFingerprint", eventFingerprint)
                .update();
        if (claimed == 0) {
            ProjectionEventIdentity existing = loadEvent(eventId);
            validateEvent(existing, topic, eventFingerprint);
            upgradeLegacyFingerprint(eventId, topic, eventFingerprint, existing);
            return false;
        }

        int delta = switch (interactionType) {
            case "LIKE" -> 1;
            case "UNLIKE" -> -1;
            case "FAVORITE", "UNFAVORITE" -> 0;
            default -> throw new IllegalArgumentException("Unsupported interaction type: " + interactionType);
        };
        counterCoordinator.apply(ContentCounterProjectionCoordinator.CounterType.LIKE,
                postId, event.occurredAt(), eventId, sourceVersion, absoluteLikeCount, delta);
        return true;
    }

    private ProjectionEventIdentity loadEvent(String eventId) {
        return jdbcClient.sql("""
                        SELECT topic, event_fingerprint
                        FROM interaction_projection_dedup
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new ProjectionEventIdentity(
                        rs.getString("topic"), rs.getString("event_fingerprint")))
                .single();
    }

    private void upgradeLegacyFingerprint(String eventId, String topic, String eventFingerprint,
                                          ProjectionEventIdentity existing) {
        if (!LEGACY_FINGERPRINT.equals(existing.eventFingerprint())) return;
        jdbcClient.sql("""
                        UPDATE interaction_projection_dedup
                        SET event_fingerprint = :eventFingerprint
                        WHERE event_id = :eventId AND event_fingerprint = :legacyFingerprint
                        """)
                .param("eventId", eventId)
                .param("eventFingerprint", eventFingerprint)
                .param("legacyFingerprint", LEGACY_FINGERPRINT)
                .update();
        validateEvent(loadEvent(eventId), topic, eventFingerprint);
    }

    private void validateEvent(ProjectionEventIdentity existing, String topic, String eventFingerprint) {
        if (!topic.equals(existing.topic())) {
            throw new IllegalArgumentException("Event ID is already bound to topic " + existing.topic());
        }
        if (!LEGACY_FINGERPRINT.equals(existing.eventFingerprint())
                && !eventFingerprint.equals(existing.eventFingerprint())) {
            throw new IllegalArgumentException("Event ID is already bound to a different payload");
        }
    }

    private record ProjectionEventIdentity(String topic, String eventFingerprint) {
    }
}
