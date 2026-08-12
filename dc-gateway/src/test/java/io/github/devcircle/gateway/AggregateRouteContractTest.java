package io.github.devcircle.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateRouteContractTest {
    @Test
    void notificationWebSocketInfrastructureRemainsExplicitlyDefaultOffUntilDedicatedCutover()
            throws IOException {
        for (String path : new String[]{
                "docker-compose.prod.yml",
                "docker-compose.e2e.yml",
                "deploy/helm/devcircle/values.yaml",
                "deploy/helm/devcircle/values-prod.yaml"}) {
            assertThat(repositoryFile(path))
                    .as("secure WebSocket default in %s", path)
                    .contains("NOTIFICATION_RUNTIME_WEBSOCKET_ENABLED: \"false\"")
                    .doesNotContain("NOTIFICATION_RUNTIME_WEBSOCKET_ENABLED: \"true\"");
        }
    }

    @Test
    void contentAndMediaUseTheServletAggregateWhileCommentAndAdminStayIndependent() throws IOException {
        String configuration;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("application-aggregate.yml")) {
            assertThat(resource).as("aggregate Gateway configuration").isNotNull();
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration).contains("id: content-aggregate")
                .contains("uri: ${DC_CONTENT_AGGREGATE_URI:http://localhost:8202}")
                .contains("Path=/api/posts/**,/api/deduction/**,/api/content/**,/api/files/**")
                .contains("id: content-comments")
                .contains("uri: ${DC_COMMENT_URI:http://localhost:8104}")
                .contains("id: content-moderation")
                .contains("uri: ${DC_ADMIN_URI:http://localhost:8110}")
                .doesNotContain("DC_CONTENT_URI:http://localhost:8103");
    }

    @Test
    void identityAuthAndDashboardRoutesCanBeRolledBackIndependently() throws IOException {
        String configuration;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("application-aggregate.yml")) {
            assertThat(resource).as("aggregate Gateway configuration").isNotNull();
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration)
                .contains("id: identity-auth")
                .contains("uri: ${DC_AUTH_URI:http://localhost:8201}")
                .contains("Path=/api/auth/**")
                .contains("id: identity-dashboard")
                .contains("uri: ${DC_IDENTITY_URI:http://localhost:8201}")
                .contains("Path=/api/me/**")
                .contains("id: identity-user")
                .contains("uri: ${DC_USER_URI:${DC_IDENTITY_URI:http://localhost:8201}}")
                .contains("id: identity-interaction-compatibility")
                .contains("uri: ${DC_INTERACTION_URI:${DC_IDENTITY_URI:http://localhost:8201}}")
                .contains("id: identity-notification-compatibility")
                .contains("Path=/api/notifications/**,/ws/notifications")
                .doesNotContain("/ws/notifications/**")
                .doesNotContain("Path=/api/auth/**,/api/me/**")
                .doesNotContain("Path=/api/auth/**,/api/users/**");
    }

    @Test
    void productionOwnsInteractionsInIdentityAndLegacyInteractionIsRollbackOnly() throws IOException {
        String production = repositoryFile("docker-compose.prod.yml");
        String e2e = repositoryFile("docker-compose.e2e.yml");
        String legacyOverlay = repositoryFile(
                "deploy/compose/docker-compose.legacy-interaction-rollback.yml");
        String productionPrometheus = repositoryFile("deploy/prometheus/prometheus.prod.yml");
        String legacyPrometheus = repositoryFile(
                "deploy/prometheus/prometheus.legacy-interaction-rollback.yml");
        String rollback = repositoryFile("scripts/ops/rollback.sh");
        String restore = repositoryFile("scripts/ops/restore.sh");
        String rollbackIdentity = composeServiceBlock(legacyOverlay, "dc-identity");

        assertThat(production)
                .contains("DC_INTERACTION_URI: http://dc-identity:8201")
                .contains("DC_INTERACTION_OUTBOX_PATH: /internal/ops/identity/outbox/interaction/status")
                .contains("INTERACTION_FLYWAY_ENABLED: \"true\"")
                .contains("INTERACTION_RUNTIME_OUTBOX_ENABLED: \"true\"")
                .doesNotContain("\n  dc-interaction:");
        assertThat(e2e)
                .contains("DC_INTERACTION_URI: http://dc-identity:8201")
                .contains("profiles: [\"legacy-interaction\"]");
        assertThat(legacyOverlay)
                .contains("\n  dc-interaction:")
                .contains("DC_INTERACTION_URI: http://dc-interaction:8105")
                .contains("SPRINGDOC_API_DOCS_ENABLED: \"false\"")
                .contains("SPRINGDOC_SWAGGER_UI_ENABLED: \"false\"");
        assertThat(rollbackIdentity)
                .contains("INTERACTION_FLYWAY_ENABLED: \"false\"")
                .contains("INTERACTION_RUNTIME_OUTBOX_ENABLED: \"false\"");
        assertThat(productionPrometheus)
                .doesNotContain("dc-interaction:8105")
                .doesNotContain("service: dc-interaction");
        assertThat(legacyPrometheus)
                .contains("targets: [\"dc-interaction:8105\"]")
                .contains("labels: { service: dc-interaction }");
        assertThat(rollback)
                .contains("LEGACY_INTERACTION_ROLLBACK:-false")
                .contains("docker-compose.legacy-interaction-rollback.yml")
                .contains("ROLLBACK_SERVICES+=(dc-interaction)");
        assertThat(restore)
                .contains("LEGACY_INTERACTION_ROLLBACK:-false")
                .contains("docker-compose.legacy-interaction-rollback.yml")
                .contains("APP_SERVICES+=(dc-interaction)");
    }

    @Test
    void productionOwnsUsersInIdentityAndLegacyUserIsAnExplicitRollbackOnly() throws IOException {
        String production = repositoryFile("docker-compose.prod.yml");
        String e2e = repositoryFile("docker-compose.e2e.yml");
        String legacyOverlay = repositoryFile("deploy/compose/docker-compose.legacy-user-rollback.yml");
        String productionPrometheus = repositoryFile("deploy/prometheus/prometheus.prod.yml");
        String legacyPrometheus = repositoryFile(
                "deploy/prometheus/prometheus.legacy-user-rollback.yml");
        String rollback = repositoryFile("scripts/ops/rollback.sh");
        String restore = repositoryFile("scripts/ops/restore.sh");
        String rollbackIdentity = composeServiceBlock(legacyOverlay, "dc-identity");
        String rollbackPrometheus = composeServiceBlock(legacyOverlay, "prometheus");

        assertThat(production)
                .contains("DC_USER_URI: http://dc-identity:8201")
                .doesNotContain("\n  dc-user:");
        assertThat(e2e)
                .contains("DC_USER_URI: http://dc-identity:8201")
                .doesNotContain("\n  dc-user:");
        assertThat(legacyOverlay)
                .contains("\n  dc-user:")
                .contains("DC_USER_URI: http://dc-user:8102")
                .contains("USER_RUNTIME_CONSUMER_ENABLED: \"false\"")
                .contains("USER_PROFILE_BACKFILL_ENABLED: \"false\"")
                .contains("USER_RUNTIME_OUTBOX_ENABLED: \"false\"")
                .contains("USER_FLYWAY_ENABLED: \"false\"");
        assertThat(rollbackIdentity)
                .contains("DC_USER_URI: http://dc-user:8102")
                .contains("USER_RUNTIME_CONSUMER_ENABLED: \"false\"")
                .contains("USER_PROFILE_BACKFILL_ENABLED: \"false\"")
                .contains("USER_RUNTIME_OUTBOX_ENABLED: \"false\"")
                .contains("USER_FLYWAY_ENABLED: \"false\"")
                .contains("dc-user: { condition: service_healthy }");
        assertThat(rollbackPrometheus)
                .contains("./deploy/prometheus/prometheus.legacy-user-rollback.yml:"
                        + "/etc/prometheus/prometheus.yml:ro");
        assertThat(productionPrometheus)
                .doesNotContain("dc-user:8102")
                .doesNotContain("service: dc-user");
        assertThat(legacyPrometheus)
                .contains("metrics_path: /actuator/prometheus")
                .contains("targets: [\"dc-user:8102\"]")
                .contains("labels: { service: dc-user }");
        assertThat(rollback)
                .contains("LEGACY_USER_ROLLBACK:-false")
                .contains("docker-compose.legacy-user-rollback.yml")
                .contains("ROLLBACK_SERVICES+=(dc-user)");
        assertThat(restore)
                .contains("LEGACY_USER_ROLLBACK:-false")
                .contains("docker-compose.legacy-user-rollback.yml")
                .contains("APP_SERVICES+=(dc-user)");
    }

    @Test
    void productionAndLegacyRollbackSelectTheAuthOwnerAtTheGateway() throws IOException {
        String production = repositoryFile("docker-compose.prod.yml");
        String legacyOverlay = repositoryFile("deploy/compose/docker-compose.legacy-auth-rollback.yml");
        String rollback = repositoryFile("scripts/ops/rollback.sh");
        String restore = repositoryFile("scripts/ops/restore.sh");

        assertThat(production)
                .contains("SPRING_PROFILES_ACTIVE: aggregate")
                .contains("DC_AUTH_URI: http://dc-identity:8201")
                .contains("DC_IDENTITY_URI: http://dc-identity:8201");
        assertThat(legacyOverlay)
                .contains("dc-gateway:")
                .contains("DC_AUTH_URI: http://dc-auth:8101")
                .contains("dc-auth: { condition: service_healthy }")
                .doesNotContain("dc-identity:\n    environment:\n      DC_AUTH_URI");
        assertThat(rollback)
                .contains("LEGACY_AUTH_ROLLBACK:-false")
                .contains("docker-compose.legacy-auth-rollback.yml")
                .contains("ROLLBACK_SERVICES=(dc-auth");
        assertThat(restore)
                .contains("LEGACY_AUTH_ROLLBACK:-false")
                .contains("docker-compose.legacy-auth-rollback.yml")
                .contains("APP_SERVICES+=(dc-auth)");
    }

    @Test
    void searchUsesDiscoveryDirectlyWhileRecommendationsKeepTheirOwnOwner() throws IOException {
        String configuration;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("application-aggregate.yml")) {
            assertThat(resource).as("aggregate Gateway configuration").isNotNull();
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration)
                .contains("id: discovery-search")
                .contains("uri: ${DC_SEARCH_URI:http://localhost:8203}")
                .contains("Path=/api/search/**")
                .contains("id: discovery-recommend")
                .contains("uri: ${DC_RECOMMEND_URI:http://localhost:8107}")
                .contains("Path=/api/recommend/**")
                .doesNotContain("id: discovery\n")
                .doesNotContain("Path=/api/search/**,/api/recommend/**");
    }

    private String repositoryFile(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IOException("Cannot locate repository file: " + relativePath);
    }

    private String composeServiceBlock(String compose, String service) {
        String marker = "\n  " + service + ":";
        int start = compose.indexOf(marker);
        assertThat(start).as("Compose service %s", service).isGreaterThanOrEqualTo(0);
        int candidate = compose.indexOf("\n  ", start + marker.length());
        while (candidate >= 0 && candidate + 3 < compose.length()
                && Character.isWhitespace(compose.charAt(candidate + 3))) {
            candidate = compose.indexOf("\n  ", candidate + 3);
        }
        return compose.substring(start, candidate < 0 ? compose.length() : candidate);
    }
}
