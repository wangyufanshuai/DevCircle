package io.github.devcircle.messaging.config;

import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import io.github.devcircle.messaging.outbox.OutboxInternalController;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka outbox publishing configuration.
 *
 * Kept in a dedicated non-deployment support library so {@code dc-common}
 * does not pull JDBC and Kafka infrastructure into every domain module.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class ReliableMessagingConfig {
    @Bean
    @ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker reliableOutboxWorker(JdbcClient jdbcClient,
                                               KafkaTemplate<String, String> kafkaTemplate,
                                               @Value("${devcircle.outbox.batch-size:50}") int batchSize,
                                               @Value("${devcircle.outbox.max-attempts:8}") int maxAttempts,
                                               @Value("${devcircle.outbox.send-timeout-ms:15000}") long sendTimeoutMs) {
        return new ReliableOutboxWorker(jdbcClient, kafkaTemplate, batchSize, maxAttempts, sendTimeoutMs);
    }

    @Bean
    OutboxStatusService outboxStatusService(
            JdbcClient jdbcClient,
            @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new OutboxStatusService(jdbcClient, serviceName);
    }

    @Bean
    OutboxInternalController outboxInternalController(
            OutboxStatusService statusService,
            @Value("${devcircle.internal.ops-key:local-development-internal-ops-key}") String internalOpsKey) {
        return new OutboxInternalController(statusService, internalOpsKey);
    }

}
