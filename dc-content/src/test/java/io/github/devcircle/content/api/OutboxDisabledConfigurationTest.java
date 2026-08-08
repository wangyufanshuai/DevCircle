package io.github.devcircle.content.api;

import io.github.devcircle.common.event.OutboxPublisher;
import io.github.devcircle.common.event.ReliableOutboxWorker;
import io.github.devcircle.content.infrastructure.JdbcOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxDisabledConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxComponents.class);

    @Test
    void disabledOutboxDoesNotLeavePublisherOrAdminControllerDependenciesBehind() {
        contextRunner.withPropertyValues("devcircle.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxController.class);
        });
    }

    @Test
    void enabledOutboxCreatesPublisherAndAdminController() {
        contextRunner.withUserConfiguration(WorkerDependency.class).run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({JdbcOutboxPublisher.class, OutboxController.class})
    static class OutboxComponents {
    }

    @Configuration(proxyBeanMethods = false)
    static class WorkerDependency {
        @Bean
        ReliableOutboxWorker reliableOutboxWorker() {
            return mock(ReliableOutboxWorker.class);
        }
    }
}
