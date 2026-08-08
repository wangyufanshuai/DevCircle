package io.github.devcircle.admin.application;

import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.common.event.OutboxStatusView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

        OutboxFleetSnapshot result = service.snapshot();

        assertThat(result.outboxScope()).isEqualTo("FLEET");
        assertThat(result.outboxComplete()).isTrue();
        assertThat(result.outboxBacklog()).isEqualTo(7);
        assertThat(result.outboxRetryable()).isEqualTo(4);
        assertThat(result.outboxDead()).isEqualTo(3);
        assertThat(result.outboxBlockedByDead()).isEqualTo(3);
        assertThat(result.degraded()).isTrue();
        assertThat(result.outboxDegradationReason()).isEqualTo("OUTBOX_DEAD");
        assertThat(result.unavailableOutboxSources()).isEmpty();
        assertThat(result.outboxSources()).hasSize(5);
    }

    @Test
    void unavailableProducerMakesFleetExplicitlyIncompleteWithoutInventingZeroHealth() {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = new LinkedHashMap<>();
        clients.put("dc-auth", () -> new OutboxStatusView("dc-auth", 2, 2, 0, 0));
        clients.put("dc-user", () -> { throw new IllegalStateException("user database unavailable"); });
        clients.put("dc-content", () -> new OutboxStatusView("dc-content", 1, 0, 1, 0));
        service = new OutboxFleetHealthService(clients, Executors.newVirtualThreadPerTaskExecutor());

        OutboxFleetSnapshot result = service.snapshot();

        assertThat(result.outboxComplete()).isFalse();
        assertThat(result.outboxBacklog()).isEqualTo(3);
        assertThat(result.outboxDead()).isEqualTo(1);
        assertThat(result.degraded()).isTrue();
        assertThat(result.outboxDegradationReason()).isEqualTo("OUTBOX_SOURCE_UNAVAILABLE");
        assertThat(result.unavailableOutboxSources()).isEqualTo(List.of("dc-user"));
        assertThat(result.outboxSources())
                .anySatisfy(source -> assertThat(source)
                        .hasFieldOrPropertyWithValue("service", "dc-user")
                        .hasFieldOrPropertyWithValue("status", "UNAVAILABLE"));
    }

    @Test
    void slowProducerIsCancelledAtTheConfiguredFleetDeadline() {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = Map.of(
                "dc-content", () -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", ex);
                    }
                    return new OutboxStatusView("dc-content", 1, 1, 0, 0);
                });
        service = new OutboxFleetHealthService(
                clients, Executors.newVirtualThreadPerTaskExecutor(), 50, 1);

        long started = System.nanoTime();
        OutboxFleetSnapshot result = service.snapshot();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(250);
        assertThat(result.outboxComplete()).isFalse();
        assertThat(result.unavailableOutboxSources()).containsExactly("dc-content");
    }

    @Test
    void saturatedBulkheadFailsFastWithoutStartingMoreDownstreamCalls() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = Map.of(
                "dc-content", () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", ex);
                    }
                    return new OutboxStatusView("dc-content", 0, 0, 0, 0);
                });
        service = new OutboxFleetHealthService(
                clients, Executors.newVirtualThreadPerTaskExecutor(), 500, 1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<OutboxFleetSnapshot> first = caller.submit(service::snapshot);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            OutboxFleetSnapshot saturated = service.snapshot();

            assertThat(saturated.outboxComplete()).isFalse();
            assertThat(saturated.unavailableOutboxSources()).containsExactly("dc-content");
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).outboxComplete()).isTrue();
        } finally {
            release.countDown();
            caller.close();
        }
    }

    @Test
    void sourceIdentityMismatchIsReportedAsUnavailable() {
        Map<String, OutboxFleetHealthService.OutboxNodeClient> clients = Map.of(
                "dc-content", () -> new OutboxStatusView("dc-user", 1, 1, 0, 0));
        service = new OutboxFleetHealthService(
                clients, Executors.newVirtualThreadPerTaskExecutor(), 200, 1);

        OutboxFleetSnapshot result = service.snapshot();

        assertThat(result.outboxComplete()).isFalse();
        assertThat(result.unavailableOutboxSources()).containsExactly("dc-content");
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
