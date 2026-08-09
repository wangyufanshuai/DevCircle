package io.github.devcircle.admin.observability;

import io.github.devcircle.admin.application.OutboxFleetHealthService;
import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxFleetMetricsTest {
    private final OutboxFleetHealthService healthService = mock(OutboxFleetHealthService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxFleetMetrics metrics = new OutboxFleetMetrics(healthService, registry);

    @Test
    void publishesTheFleetSnapshotWithoutInventingMissingSources() {
        when(healthService.snapshot()).thenReturn(new OutboxFleetSnapshot(
                "FLEET", false, 9, 7, 2, 3,
                List.of(OutboxSourceView.available("dc-content", 9, 7, 2, 3),
                        OutboxSourceView.unavailable("dc-user")),
                List.of("dc-user"), true, "OUTBOX_SOURCE_UNAVAILABLE"));

        metrics.refresh();

        assertThat(value("devcircle.outbox.fleet.backlog")).isEqualTo(9);
        assertThat(value("devcircle.outbox.fleet.retryable")).isEqualTo(7);
        assertThat(value("devcircle.outbox.fleet.dead")).isEqualTo(2);
        assertThat(value("devcircle.outbox.fleet.blocked")).isEqualTo(3);
        assertThat(value("devcircle.outbox.fleet.complete")).isZero();
        assertThat(value("devcircle.outbox.fleet.unavailable.sources")).isEqualTo(1);
    }

    @Test
    void refreshFailureMarksTheSnapshotIncompleteAndKeepsLastKnownCounts() {
        when(healthService.snapshot())
                .thenReturn(new OutboxFleetSnapshot("FLEET", true, 4, 4, 0, 0,
                        List.of(), List.of(), false, null))
                .thenThrow(new IllegalStateException("fleet unavailable"));

        metrics.refresh();
        metrics.refresh();

        assertThat(value("devcircle.outbox.fleet.backlog")).isEqualTo(4);
        assertThat(value("devcircle.outbox.fleet.complete")).isZero();
        assertThat(value("devcircle.outbox.fleet.unavailable.sources")).isEqualTo(1);
    }

    private double value(String name) {
        return registry.get(name).tag("scope", "fleet").gauge().value();
    }
}
