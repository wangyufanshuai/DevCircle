package io.github.devcircle.admin.application.query;

import java.util.List;

public interface AdminOpsReadRepository {
    HealthMetrics healthMetrics();

    DegradationMetrics degradationMetrics();

    List<OutboxEventView> outboxEvents(String status, String topic, int size, long offset);

    record HealthMetrics(long posts, long pendingModeration, long riskUsers) {
    }

    record DegradationMetrics(long recommendImpressions, long recommendEvents, long pendingModeration) {
    }
}
