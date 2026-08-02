package io.github.devcircle.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventFingerprintTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fingerprintBindsEnvelopeAndPayloadButNotTheDeduplicationKey() throws Exception {
        Instant occurredAt = Instant.parse("2026-08-02T00:00:00Z");
        ValidatedEvent first = event(UUID.randomUUID(), occurredAt, Map.of("receiverId", 7L, "status", "FOLLOWED"));
        ValidatedEvent sameEventBody = new ValidatedEvent(UUID.randomUUID(), EventTopics.FOLLOW_CHANGED, 1,
                occurredAt, "dc-user", "7",
                objectMapper.readTree("{\"status\":\"FOLLOWED\",\"receiverId\":7}"));
        ValidatedEvent changedPayload = event(UUID.randomUUID(), occurredAt,
                Map.of("receiverId", 7L, "status", "UNFOLLOWED"));

        assertThat(EventFingerprint.sha256(first))
                .hasSize(64)
                .isEqualTo(EventFingerprint.sha256(sameEventBody))
                .isNotEqualTo(EventFingerprint.sha256(changedPayload));
    }

    @Test
    void nullEventsAreRejected() {
        assertThatThrownBy(() -> EventFingerprint.sha256(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("event");
    }

    @Test
    void nestedObjectsAndArraysAreCanonicalButEveryEnvelopeFieldRemainsBound() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-02T00:00:00Z");
        ValidatedEvent original = new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1, occurredAt,
                "dc-user", "7", objectMapper.readTree("{\"nested\":{\"b\":null,\"a\":[1,2]}}"));
        ValidatedEvent reordered = new ValidatedEvent(UUID.randomUUID(), EventTopics.FOLLOW_CHANGED, 1, occurredAt,
                "dc-user", "7", objectMapper.readTree("{\"nested\":{\"a\":[1,2],\"b\":null}}"));

        String fingerprint = EventFingerprint.sha256(original);
        assertThat(EventFingerprint.sha256(reordered)).isEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, "other.type", 1, occurredAt,
                "dc-user", "7", original.payload()))).isNotEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 2, occurredAt,
                "dc-user", "7", original.payload()))).isNotEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1,
                occurredAt.plusSeconds(1), "dc-user", "7", original.payload()))).isNotEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1, occurredAt,
                "other-producer", "7", original.payload()))).isNotEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1, occurredAt,
                "dc-user", "8", original.payload()))).isNotEqualTo(fingerprint);
        assertThat(EventFingerprint.sha256(new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1, occurredAt,
                "dc-user", "7", objectMapper.readTree("{\"nested\":{\"a\":[2,1],\"b\":null}}"))))
                .isNotEqualTo(fingerprint);
    }

    @Test
    void nullPayloadsHaveADeterministicBinding() {
        ValidatedEvent event = new ValidatedEvent(UUID.randomUUID(), EventTopics.FOLLOW_CHANGED, 1,
                Instant.parse("2026-08-02T00:00:00Z"), "dc-user", "7", null);

        assertThat(EventFingerprint.sha256(event)).hasSize(64);
    }

    private ValidatedEvent event(UUID eventId, Instant occurredAt, Map<String, Object> payload) {
        return new ValidatedEvent(eventId, EventTopics.FOLLOW_CHANGED, 1, occurredAt, "dc-user", "7",
                objectMapper.valueToTree(payload));
    }
}
