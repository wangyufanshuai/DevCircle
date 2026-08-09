package io.github.devcircle.messaging.outbox;

public record OutboxStatusView(
        String service,
        long backlog,
        long retryable,
        long dead,
        long blockedByDead
) {
}
