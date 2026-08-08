package io.github.devcircle.common.event;

public record OutboxStatusView(
        String service,
        long backlog,
        long retryable,
        long dead,
        long blockedByDead
) {
}
