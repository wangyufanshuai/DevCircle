package io.github.devcircle.common.event;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventRecord(
        Long id,
        UUID eventId,
        String topic,
        String aggregateType,
        String aggregateId,
        String payload,
        String status,
        Instant createdAt
) {
}

