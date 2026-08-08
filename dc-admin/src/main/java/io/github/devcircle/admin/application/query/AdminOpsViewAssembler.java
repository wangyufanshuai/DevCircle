package io.github.devcircle.admin.application.query;

import io.github.devcircle.admin.application.query.AdminOpsReadRepository.DegradationMetrics;
import io.github.devcircle.admin.application.query.AdminOpsReadRepository.HealthMetrics;

public final class AdminOpsViewAssembler {
    private AdminOpsViewAssembler() {
    }

    public static OpsHealthView health(HealthMetrics local, OutboxFleetSnapshot fleet) {
        boolean databaseAvailable = local != null;
        return new OpsHealthView(
                databaseAvailable ? "UP" : "DOWN",
                fleet.outboxBacklog(),
                fleet.outboxRetryable(),
                fleet.outboxDead(),
                fleet.outboxBlockedByDead(),
                fleet.outboxScope(),
                fleet.outboxComplete(),
                fleet.outboxSources(),
                fleet.unavailableOutboxSources(),
                databaseAvailable ? local.pendingModeration() : null,
                databaseAvailable ? local.riskUsers() : null,
                databaseAvailable ? local.posts() : null,
                databaseAvailable ? "DB" : "HEALTH_CHECK",
                !databaseAvailable || fleet.degraded(),
                databaseAvailable ? fleet.outboxDegradationReason() : "ADMIN_DATABASE_UNAVAILABLE",
                fleet.outboxDegradationReason());
    }

    public static DegradationView degradation(DegradationMetrics local, OutboxFleetSnapshot fleet) {
        boolean databaseAvailable = local != null;
        return new DegradationView(
                !databaseAvailable || fleet.degraded(),
                databaseAvailable ? "DB" : "HEALTH_CHECK",
                databaseAvailable ? fleet.outboxDegradationReason() : "DATABASE_UNAVAILABLE",
                fleet.outboxBacklog(),
                fleet.outboxRetryable(),
                fleet.outboxDead(),
                fleet.outboxBlockedByDead(),
                fleet.outboxScope(),
                fleet.outboxComplete(),
                fleet.outboxSources(),
                fleet.unavailableOutboxSources(),
                databaseAvailable ? local.recommendImpressions() : null,
                databaseAvailable ? local.recommendEvents() : null,
                databaseAvailable ? local.pendingModeration() : null,
                fleet.outboxDegradationReason());
    }
}
