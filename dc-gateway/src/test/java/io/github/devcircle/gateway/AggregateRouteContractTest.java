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
    void contentRequestsCannotBypassTheContentAggregateFacade() throws IOException {
        String configuration;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("application-aggregate.yml")) {
            assertThat(resource).as("aggregate Gateway configuration").isNotNull();
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration).contains("id: content-aggregate")
                .contains("Path=/api/posts/**,/api/deduction/**,/api/content/**,/api/comments/**,/api/files/**,/api/admin/**")
                .doesNotContain("id: content-comments")
                .doesNotContain("DC_COMMENT_URI:http://localhost:8104");
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
                .contains("id: identity-user-compatibility")
                .contains("id: identity-interaction-compatibility")
                .contains("id: identity-notification-compatibility")
                .doesNotContain("Path=/api/auth/**,/api/me/**")
                .doesNotContain("Path=/api/auth/**,/api/users/**");
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
}
