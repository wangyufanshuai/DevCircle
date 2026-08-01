package io.github.devcircle.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(UUID eventId,
                            String type,
                            int version,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
                            String producer,
                            String aggregateId,
                            Map<String, Object> payload) {
    public static EventEnvelope now(String type, String producer, String aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), producer, aggregateId,
                payload == null ? Map.of() : Map.copyOf(payload));
    }
}
