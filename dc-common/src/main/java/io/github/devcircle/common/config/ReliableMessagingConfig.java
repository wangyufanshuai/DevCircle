package io.github.devcircle.common.config;

import io.github.devcircle.common.event.ReliableOutboxWorker;
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
 * Keeping Kafka types out of {@link CommonWebConfig} is important because
 * {@code dc-common} is intentionally an optional dependency. Services such
 * as dc-user must be able to start without the Kafka client on their runtime
 * classpath.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class ReliableMessagingConfig {
    @Bean
    @ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker reliableOutboxWorker(JdbcClient jdbcClient,
                                               KafkaTemplate<String, String> kafkaTemplate,
                                               @Value("${devcircle.outbox.send-timeout-ms:15000}") long sendTimeoutMs) {
        return new ReliableOutboxWorker(jdbcClient, kafkaTemplate, 50, 8, sendTimeoutMs);
    }

}
