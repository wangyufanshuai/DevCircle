package io.github.devcircle.identity;

import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

/** Publishes and reports the embedded Interaction domain's outbox from its own schema. */
@Configuration(proxyBeanMethods = false)
class IdentityInteractionOutboxConfig {
    @Bean("interactionReliableOutboxWorker")
    @ConditionalOnProperty(prefix = "devcircle.interaction-runtime.outbox", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker interactionReliableOutboxWorker(
            @Qualifier("interactionJdbcClient") JdbcClient interactionJdbcClient,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${devcircle.interaction-runtime.outbox.batch-size:${devcircle.outbox.batch-size:50}}")
            int batchSize,
            @Value("${devcircle.interaction-runtime.outbox.max-attempts:${devcircle.outbox.max-attempts:8}}")
            int maxAttempts,
            @Value("${devcircle.interaction-runtime.outbox.send-timeout-ms:${devcircle.outbox.send-timeout-ms:15000}}")
            long sendTimeoutMs) {
        return new ReliableOutboxWorker(
                interactionJdbcClient, kafkaTemplate, batchSize, maxAttempts, sendTimeoutMs);
    }

    @Bean
    IdentityInteractionOutboxStatus interactionOutboxStatus(
            @Qualifier("interactionJdbcClient") JdbcClient interactionJdbcClient,
            @Value("${devcircle.interaction-runtime.outbox.service-name:dc-interaction}") String serviceName) {
        return new IdentityInteractionOutboxStatus(
                new OutboxStatusService(interactionJdbcClient, serviceName));
    }
}
