package io.github.devcircle.common.config;

import io.github.devcircle.common.event.ReliableOutboxWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka-only infrastructure configuration.
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
                                               KafkaTemplate<String, String> kafkaTemplate) {
        return new ReliableOutboxWorker(jdbcClient, kafkaTemplate, 50, 8);
    }

    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxAttempts(4);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }
}
