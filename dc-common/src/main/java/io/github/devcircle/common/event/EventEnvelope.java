package io.github.devcircle.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(UUID eventId,
                            String type,
                            int version,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
                            String producer,
                            String aggregateId,
                            Map<String, Object> payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        requireText(type, "type");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(producer, "producer");
        requireText(aggregateId, "aggregateId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static EventEnvelope now(String type, String producer, String aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), producer, aggregateId,
                payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be a non-blank value without surrounding whitespace");
        }
    }
}
