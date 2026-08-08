package io.github.devcircle.admin.application;

import io.github.devcircle.admin.infrastructure.JdbcAdminOpsReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxFleetHealthConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FleetConfiguration.class)
            .withPropertyValues(
                    "devcircle.internal.auth-uri=http://127.0.0.1:8101",
                    "devcircle.internal.user-uri=http://127.0.0.1:8102",
                    "devcircle.internal.content-uri=http://127.0.0.1:8103",
                    "devcircle.internal.comment-uri=http://127.0.0.1:8104",
                    "devcircle.internal.interaction-uri=http://127.0.0.1:8105",
                    "devcircle.internal.ops-key=test-internal-operations-key");

    @Test
    void productionOperationsServicesAreWiredBySpring() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxFleetHealthService.class);
            assertThat(context).hasSingleBean(AdminOpsQueryService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OutboxFleetHealthService.class, AdminOpsQueryService.class, JdbcAdminOpsReadRepository.class})
    static class FleetConfiguration {
        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }
    }
}
