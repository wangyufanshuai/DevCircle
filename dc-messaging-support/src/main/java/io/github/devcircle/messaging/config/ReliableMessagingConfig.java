package io.github.devcircle.messaging.config;

import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import io.github.devcircle.messaging.outbox.OutboxInternalController;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
    private static final String DEVELOPMENT_OPERATIONS_KEY = "local-development-internal-ops-key";

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
            @Value("${devcircle.outbox.service-name:${spring.application.name:unknown-service}}") String serviceName) {
        return new OutboxStatusService(jdbcClient, serviceName);
    }

    @Bean
    OutboxInternalController outboxInternalController(
            OutboxStatusService statusService,
            @Value("${devcircle.internal.ops-key:" + DEVELOPMENT_OPERATIONS_KEY + "}") String internalOpsKey) {
        return new OutboxInternalController(statusService, internalOpsKey);
    }

    @Bean
    SmartInitializingSingleton reliableMessagingProductionGuard(
            @Value("${devcircle.production:false}") boolean production,
            @Value("${devcircle.internal.ops-key:" + DEVELOPMENT_OPERATIONS_KEY + "}") String internalOpsKey) {
        return () -> validateInternalOperationsKey(production, internalOpsKey);
    }

    static void validateInternalOperationsKey(boolean production, String internalOpsKey) {
        if (!production) {
            return;
        }
        String candidate = internalOpsKey == null ? "" : internalOpsKey.trim();
        String lowered = candidate.toLowerCase(java.util.Locale.ROOT);
        long distinctCharacters = candidate.chars().distinct().count();
        if (candidate.length() < 32 || distinctCharacters < 12
                || lowered.contains("local-development")
                || lowered.contains("replace-with")
                || lowered.contains("changeme")) {
            throw new IllegalStateException(
                    "DEVCIRCLE_INTERNAL_OPS_KEY must contain at least 32 high-diversity non-placeholder characters in production");
        }
    }

}
