package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentCommentProjectionRepository {
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final JdbcClient jdbcClient;

    public ContentCommentProjectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public boolean apply(ValidatedEvent event, Long postId, String changeType) {
        String eventId = event.eventId().toString();
        String topic = event.type();
        String eventFingerprint = EventFingerprint.sha256(event);
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO comment_projection_dedup(event_id, topic, event_fingerprint)
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

        int delta;
        if ((EventTopics.COMMENT_CREATED.equals(topic) || EventTopics.COMMENT_CHANGED.equals(topic))
                && "CREATED".equals(changeType)) {
            delta = 1;
        } else if (EventTopics.COMMENT_CHANGED.equals(topic)
                && ("DELETED".equals(changeType) || "HIDDEN".equals(changeType))) {
            delta = -1;
        } else {
            throw new IllegalArgumentException("Unsupported comment event: " + topic + "/" + changeType);
        }
        int updated = jdbcClient.sql("""
                        UPDATE post
                        SET comment_count = GREATEST(comment_count + :delta, 0)
                        WHERE id = :postId
                        """)
                .param("delta", delta).param("postId", postId).update();
        if (updated != 1) {
            throw new IllegalStateException("Comment event references missing post " + postId);
        }
        return true;
    }

    private ProjectionEventIdentity loadEvent(String eventId) {
        return jdbcClient.sql("""
                        SELECT topic, event_fingerprint
                        FROM comment_projection_dedup
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
        int upgraded = jdbcClient.sql("""
                        UPDATE comment_projection_dedup
                        SET event_fingerprint = :eventFingerprint
                        WHERE event_id = :eventId AND event_fingerprint = :legacyFingerprint
                        """)
                .param("eventId", eventId)
                .param("eventFingerprint", eventFingerprint)
                .param("legacyFingerprint", LEGACY_FINGERPRINT)
                .update();
        if (upgraded == 0) validateEvent(loadEvent(eventId), topic, eventFingerprint);
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
