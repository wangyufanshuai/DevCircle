package io.github.devcircle.interaction;

import io.github.devcircle.messaging.config.ReliableMessagingConfig;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReliableMessagingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(ReliableMessagingConfig.class, TestDependencies.class)
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:65535");

    @Test
    void bootKafkaStarterProvidesTheTemplateRequiredByTheOutboxWorker() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KafkaTemplate.class);
            assertThat(context).hasSingleBean(ReliableOutboxWorker.class);
        });
    }

    @Test
    void explicitlyDisabledOutboxDoesNotCreateAWorker() {
        contextRunner.withPropertyValues("devcircle.outbox.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(KafkaTemplate.class);
            assertThat(context).doesNotHaveBean(ReliableOutboxWorker.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }
    }
}
