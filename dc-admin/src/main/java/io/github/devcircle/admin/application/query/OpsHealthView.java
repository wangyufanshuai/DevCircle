package io.github.devcircle.admin.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpsHealthView(
        String database,
        long outboxBacklog,
        long outboxRetryable,
        long outboxDead,
        long outboxBlockedByDead,
        String outboxScope,
        boolean outboxComplete,
        List<OutboxSourceView> outboxSources,
        List<String> unavailableOutboxSources,
        Long pendingModeration,
        Long riskUsers,
        Long posts,
        String source,
        boolean degraded,
        String degradationReason,
        String outboxDegradationReason
) {
}
