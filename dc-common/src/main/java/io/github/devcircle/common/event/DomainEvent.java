package io.github.devcircle.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
        UUID eventId,
        String topic,
        String aggregateType,
        String aggregateId,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static DomainEvent now(String topic, String aggregateType, String aggregateId, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), topic, aggregateType, aggregateId, payload, Instant.now());
    }
}

