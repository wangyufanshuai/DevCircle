package io.github.devcircle.admin.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

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
    void productionConstructorIsSelectedBySpring() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxFleetHealthService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OutboxFleetHealthService.class)
    static class FleetConfiguration {
    }
}
