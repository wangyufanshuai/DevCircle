package io.github.devcircle.admin.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboxSourceView(
        String service,
        String status,
        Long backlog,
        Long retryable,
        Long dead,
        Long blockedByDead
) {
    public static OutboxSourceView available(String service, long backlog, long retryable,
                                             long dead, long blockedByDead) {
        return new OutboxSourceView(service, "UP", backlog, retryable, dead, blockedByDead);
    }

    public static OutboxSourceView unavailable(String service) {
        return new OutboxSourceView(service, "UNAVAILABLE", null, null, null, null);
    }
}
