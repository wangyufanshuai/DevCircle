package io.github.devcircle.admin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.application.query.OpsHealthView;
import io.github.devcircle.admin.application.query.OutboxEventView;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminOpsViewJsonTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void healthKeepsFleetFieldNamesAndOmitsOnlyUnavailableLocalMetrics() throws Exception {
        OpsHealthView health = new OpsHealthView(
                "DOWN", 4, 2, 1, 1, "FLEET", false,
                List.of(OutboxSourceView.unavailable("dc-user")), List.of("dc-user"),
                null, null, null, "HEALTH_CHECK", true,
                "ADMIN_DATABASE_UNAVAILABLE", "OUTBOX_SOURCE_UNAVAILABLE");

        JsonNode json = objectMapper.valueToTree(health);

        assertThat(json.get("database").asText()).isEqualTo("DOWN");
        assertThat(json.get("outboxScope").asText()).isEqualTo("FLEET");
        assertThat(json.get("outboxComplete").asBoolean()).isFalse();
        assertThat(json.get("degradationReason").asText()).isEqualTo("ADMIN_DATABASE_UNAVAILABLE");
        assertThat(json.get("unavailableOutboxSources").get(0).asText()).isEqualTo("dc-user");
        assertThat(json.get("outboxSources").get(0).has("backlog")).isFalse();
        assertThat(json.has("pendingModeration")).isFalse();
    }

    @Test
    void outboxEventRetainsNullableTimestampAndErrorFields() {
        OutboxEventView event = new OutboxEventView(
                7, "event-7", "post.submitted", "POST", "42", "NEW", 0,
                null, null, null, null, null);

        JsonNode json = objectMapper.valueToTree(event);

        assertThat(json.has("nextAttemptAt")).isTrue();
        assertThat(json.get("nextAttemptAt").isNull()).isTrue();
        assertThat(json.has("lockedAt")).isTrue();
        assertThat(json.has("lastError")).isTrue();
        assertThat(json.has("createdAt")).isTrue();
        assertThat(json.has("publishedAt")).isTrue();
    }
}
