package io.github.devcircle.admin.observability;

import io.github.devcircle.admin.application.OutboxFleetHealthService;
import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxFleetMetrics {
    private static final Logger log = LoggerFactory.getLogger(OutboxFleetMetrics.class);

    private final OutboxFleetHealthService fleetHealthService;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong retryable = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong blockedByDead = new AtomicLong();
    private final AtomicLong complete = new AtomicLong();
    private final AtomicLong unavailableSources = new AtomicLong();

    public OutboxFleetMetrics(OutboxFleetHealthService fleetHealthService, MeterRegistry meterRegistry) {
        this.fleetHealthService = fleetHealthService;
        gauge(meterRegistry, "devcircle.outbox.fleet.backlog", "Known unpublished events", backlog);
        gauge(meterRegistry, "devcircle.outbox.fleet.retryable", "Known retryable outbox events", retryable);
        gauge(meterRegistry, "devcircle.outbox.fleet.dead", "Known dead outbox events", dead);
        gauge(meterRegistry, "devcircle.outbox.fleet.blocked", "Events blocked behind a dead predecessor", blockedByDead);
        gauge(meterRegistry, "devcircle.outbox.fleet.complete", "One when every outbox source was sampled", complete);
        gauge(meterRegistry, "devcircle.outbox.fleet.unavailable.sources", "Unavailable outbox sources", unavailableSources);
    }

    @Scheduled(
            initialDelayString = "${devcircle.admin.outbox-metrics-initial-delay-ms:15000}",
            fixedDelayString = "${devcircle.admin.outbox-metrics-interval-ms:30000}")
    void refresh() {
        try {
            update(fleetHealthService.snapshot());
        } catch (RuntimeException failure) {
            complete.set(0);
            unavailableSources.updateAndGet(current -> Math.max(1, current));
            log.warn("Outbox fleet metrics refresh failed failure={} message={}",
                    failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    private void update(OutboxFleetSnapshot snapshot) {
        backlog.set(snapshot.outboxBacklog());
        retryable.set(snapshot.outboxRetryable());
        dead.set(snapshot.outboxDead());
        blockedByDead.set(snapshot.outboxBlockedByDead());
        complete.set(snapshot.outboxComplete() ? 1 : 0);
        unavailableSources.set(snapshot.unavailableOutboxSources().size());
    }

    private void gauge(MeterRegistry registry, String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .tag("scope", "fleet")
                .register(registry);
    }
}
