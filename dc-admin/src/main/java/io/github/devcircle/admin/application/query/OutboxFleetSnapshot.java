package io.github.devcircle.admin.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboxFleetSnapshot(
        String outboxScope,
        boolean outboxComplete,
        long outboxBacklog,
        long outboxRetryable,
        long outboxDead,
        long outboxBlockedByDead,
        List<OutboxSourceView> outboxSources,
        List<String> unavailableOutboxSources,
        boolean degraded,
        String outboxDegradationReason
) {
    public static OutboxFleetSnapshot unavailable(List<String> services) {
        List<String> unavailable = List.copyOf(services);
        return new OutboxFleetSnapshot(
                "FLEET",
                false,
                0,
                0,
                0,
                0,
                unavailable.stream().map(OutboxSourceView::unavailable).toList(),
                unavailable,
                true,
                "OUTBOX_SOURCE_UNAVAILABLE");
    }
}
