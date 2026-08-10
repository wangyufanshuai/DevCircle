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

/** Publishes and reports the embedded User domain's outbox from its own schema. */
@Configuration(proxyBeanMethods = false)
class IdentityUserOutboxConfig {
    @Bean("userReliableOutboxWorker")
    @ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker userReliableOutboxWorker(
            @Qualifier("userJdbcClient") JdbcClient userJdbcClient,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${devcircle.user-runtime.outbox.batch-size:${devcircle.outbox.batch-size:50}}") int batchSize,
            @Value("${devcircle.user-runtime.outbox.max-attempts:${devcircle.outbox.max-attempts:8}}") int maxAttempts,
            @Value("${devcircle.user-runtime.outbox.send-timeout-ms:${devcircle.outbox.send-timeout-ms:15000}}")
            long sendTimeoutMs) {
        return new ReliableOutboxWorker(userJdbcClient, kafkaTemplate, batchSize, maxAttempts, sendTimeoutMs);
    }

    @Bean
    IdentityUserOutboxStatus userOutboxStatus(
            @Qualifier("userJdbcClient") JdbcClient userJdbcClient,
            @Value("${devcircle.user-runtime.outbox.service-name:dc-user}") String serviceName) {
        return new IdentityUserOutboxStatus(new OutboxStatusService(userJdbcClient, serviceName));
    }
}
