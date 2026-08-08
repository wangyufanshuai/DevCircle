package io.github.devcircle.admin.application.query;

import java.time.Instant;

public record OutboxEventView(
        long id,
        String eventId,
        String topic,
        String aggregateType,
        String aggregateId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant lockedAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt
) {
}
