package io.github.devcircle.admin.application;

import io.github.devcircle.common.event.OutboxStatusView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxFleetHealthServiceTest {
    private OutboxFleetHealthService service;

    @AfterEach
    void close() {
        if (service != null) service.close();
    }

    @Test
    void aggregatesEveryProducerAndMarksDeadFleetAsDegraded() {
        service = service(Map.of(
                "dc-auth", status("dc-auth", 1, 1, 0, 0),
                "dc-user", status("dc-user", 3, 1, 2, 1),
                "dc-content", status("dc-content", 2, 2, 0, 0),
                "dc-comment", status("dc-comment", 0, 0, 0, 0),
                "dc-interaction", status("dc-interaction", 1, 0, 1, 2)));

        Map<String, Object> result = service.enrich(Map.of("database", "UP", "degraded", false),
                "degradationReason");

        assertThat(result)
                .containsEntry("outboxScope", "FLEET")
                .containsEntry("outboxComplete", true)
                .containsEntry("outboxBacklog", 7L)
                .containsEntry("outboxRetryable", 4L)
                .containsEntry("outboxDead", 3L)
                .containsEntry("outboxBlockedByDead", 3L)
                .containsEntry("degraded", true)
                .containsEntry("degradationReason", "OUTBOX_DEAD");
        assertThat((List<?>) result.get("unavailableOutboxSources")).isEmpty();
        assertThat((List<?>) result.get("outboxSources")).hasSize(5);
    }

    @Test
    void unavailableProducerMakesFleetExplicitlyIncompleteWithoutInventingZeroHealth() {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = new LinkedHashMap<>();
        clients.put("dc-auth", () -> new OutboxStatusView("dc-auth", 2, 2, 0, 0));
        clients.put("dc-user", () -> { throw new IllegalStateException("user database unavailable"); });
        clients.put("dc-content", () -> new OutboxStatusView("dc-content", 1, 0, 1, 0));
        service = new OutboxFleetHealthService(clients, Executors.newVirtualThreadPerTaskExecutor());

        Map<String, Object> result = service.snapshot();

        assertThat(result)
                .containsEntry("outboxComplete", false)
                .containsEntry("outboxBacklog", 3L)
                .containsEntry("outboxDead", 1L)
                .containsEntry("degraded", true)
                .containsEntry("outboxDegradationReason", "OUTBOX_SOURCE_UNAVAILABLE");
        assertThat(result.get("unavailableOutboxSources")).isEqualTo(List.of("dc-user"));
        assertThat((List<Map<String, Object>>) result.get("outboxSources"))
                .anySatisfy(source -> assertThat(source)
                        .containsEntry("service", "dc-user")
                        .containsEntry("status", "UNAVAILABLE"));
    }

    @Test
    void existingAdminDatabaseFailureTakesPrecedenceOverFleetReason() {
        service = service(Map.of("dc-content", status("dc-content", 1, 0, 1, 0)));

        Map<String, Object> result = service.enrich(
                Map.of("database", "DOWN", "degraded", true, "degradationReason", "DATABASE_UNAVAILABLE"),
                "degradationReason");

        assertThat(result).containsEntry("degraded", true)
                .containsEntry("degradationReason", "DATABASE_UNAVAILABLE")
                .containsEntry("outboxDegradationReason", "OUTBOX_DEAD");
    }

    @Test
    void unavailableFleetSourceTakesPrecedenceOverLegacyLocalOutboxReason() {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = new LinkedHashMap<>();
        clients.put("dc-auth", () -> { throw new IllegalStateException("auth timed out"); });
        clients.put("dc-content", () -> new OutboxStatusView("dc-content", 2, 1, 1, 1));
        service = new OutboxFleetHealthService(clients, Executors.newVirtualThreadPerTaskExecutor());

        Map<String, Object> result = service.enrich(
                Map.of("database", "UP", "degraded", true, "degradationReason", "OUTBOX_DEAD"),
                "degradationReason");

        assertThat(result)
                .containsEntry("outboxComplete", false)
                .containsEntry("degradationReason", "OUTBOX_SOURCE_UNAVAILABLE")
                .containsEntry("outboxDegradationReason", "OUTBOX_SOURCE_UNAVAILABLE");
    }

    private OutboxFleetHealthService service(Map<String, OutboxStatusView> statuses) {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = new LinkedHashMap<>();
        statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> clients.put(entry.getKey(), () -> entry.getValue()));
        return new OutboxFleetHealthService(clients, Executors.newVirtualThreadPerTaskExecutor());
    }

    private OutboxStatusView status(String serviceName, long backlog, long retryable, long dead, long blocked) {
        return new OutboxStatusView(serviceName, backlog, retryable, dead, blocked);
    }
}
