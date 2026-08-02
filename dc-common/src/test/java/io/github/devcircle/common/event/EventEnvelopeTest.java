package io.github.devcircle.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {
    @Test
    void nowCreatesAnImmutableEnvelopeAndDefaultsNullPayloadToEmpty() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("postId", 1001L);

        EventEnvelope event = EventEnvelope.now("post.updated", "dc-content", "1001", payload);
        payload.put("postId", 9999L);

        assertThat(event.version()).isEqualTo(1);
        assertThat(event.payload()).containsEntry("postId", 1001L);
        assertThatThrownBy(() -> event.payload().put("status", "PUBLISHED"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(EventEnvelope.now("post.updated", "dc-content", "1001", null).payload()).isEmpty();
    }

    @Test
    void rejectsInvalidEnvelopeIdentityBeforeItCanReachOutbox() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-02T00:00:00Z");

        assertThatThrownBy(() -> new EventEnvelope(null, "post.updated", 1, occurredAt,
                "dc-content", "1001", Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessage("eventId");
        assertThatThrownBy(() -> new EventEnvelope(eventId, " ", 1, occurredAt,
                "dc-content", "1001", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new EventEnvelope(eventId, "post.updated", 0, occurredAt,
                "dc-content", "1001", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> new EventEnvelope(eventId, "post.updated", 1, occurredAt,
                " dc-content", "1001", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("producer");
        assertThatThrownBy(() -> new EventEnvelope(eventId, "post.updated", 1, occurredAt,
                "dc-content", "", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggregateId");
    }
}
