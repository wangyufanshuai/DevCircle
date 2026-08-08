package io.github.devcircle.admin.application;

import io.github.devcircle.admin.application.query.AdminOpsReadRepository;
import io.github.devcircle.admin.application.query.AdminOpsReadRepository.HealthMetrics;
import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOpsQueryServiceTest {
    private AdminOpsQueryService service;

    @AfterEach
    void close() {
        if (service != null) service.close();
    }

    @Test
    void sharedDeadlineReturnsAvailableLocalMetricsWhenFleetIsSlow() throws Exception {
        AdminOpsReadRepository repository = mock(AdminOpsReadRepository.class);
        OutboxFleetHealthService fleet = mock(OutboxFleetHealthService.class);
        when(repository.healthMetrics()).thenReturn(new HealthMetrics(180, 3, 2));
        when(fleet.snapshot()).thenAnswer(ignored -> {
            Thread.sleep(300);
            return healthyFleet();
        });
        when(fleet.unavailableSnapshot()).thenReturn(unavailableFleet());
        service = new AdminOpsQueryService(repository, fleet, Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(60), 4);

        long started = System.nanoTime();
        var result = service.health();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isLessThan(220);
        assertThat(result.database()).isEqualTo("UP");
        assertThat(result.posts()).isEqualTo(180);
        assertThat(result.outboxComplete()).isFalse();
        assertThat(result.degradationReason()).isEqualTo("OUTBOX_SOURCE_UNAVAILABLE");
    }

    @Test
    void sharedDeadlineRetainsFleetEvidenceWhenDatabaseIsSlow() throws Exception {
        AdminOpsReadRepository repository = mock(AdminOpsReadRepository.class);
        OutboxFleetHealthService fleet = mock(OutboxFleetHealthService.class);
        when(repository.healthMetrics()).thenAnswer(ignored -> {
            Thread.sleep(300);
            return new HealthMetrics(180, 3, 2);
        });
        when(fleet.snapshot()).thenReturn(healthyFleet());
        when(fleet.unavailableSnapshot()).thenReturn(unavailableFleet());
        service = new AdminOpsQueryService(repository, fleet, Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(60), 4);

        var result = service.health();

        assertThat(result.database()).isEqualTo("DOWN");
        assertThat(result.outboxComplete()).isTrue();
        assertThat(result.outboxBacklog()).isEqualTo(1);
        assertThat(result.degradationReason()).isEqualTo("ADMIN_DATABASE_UNAVAILABLE");
    }

    @Test
    void paginationNormalizesPageAndSizeBeforeCalculatingOffset() {
        AdminOpsReadRepository repository = mock(AdminOpsReadRepository.class);
        OutboxFleetHealthService fleet = mock(OutboxFleetHealthService.class);
        when(repository.outboxEvents(null, "post.submitted", 200, 200L)).thenReturn(List.of());
        when(repository.outboxEvents(null, null, 1, 0L)).thenReturn(List.of());
        service = new AdminOpsQueryService(repository, fleet, Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofSeconds(1), 4);

        service.outboxEvents(" ", "post.submitted", 2, 1_000);
        service.outboxEvents(null, " ", -3, -10);

        verify(repository).outboxEvents(null, "post.submitted", 200, 200L);
        verify(repository).outboxEvents(null, null, 1, 0L);
    }

    private OutboxFleetSnapshot healthyFleet() {
        return new OutboxFleetSnapshot("FLEET", true, 1, 1, 0, 0,
                List.of(OutboxSourceView.available("dc-content", 1, 1, 0, 0)),
                List.of(), false, null);
    }

    private OutboxFleetSnapshot unavailableFleet() {
        return OutboxFleetSnapshot.unavailable(List.of("dc-content"));
    }
}
