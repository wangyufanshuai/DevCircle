package io.github.devcircle.admin.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DegradationView(
        boolean degraded,
        String source,
        String reason,
        long outboxBacklog,
        long outboxRetryable,
        long outboxDead,
        long outboxBlockedByDead,
        String outboxScope,
        boolean outboxComplete,
        List<OutboxSourceView> outboxSources,
        List<String> unavailableOutboxSources,
        Long recommendImpressions,
        Long recommendEvents,
        Long pendingModeration,
        String outboxDegradationReason
) {
}
