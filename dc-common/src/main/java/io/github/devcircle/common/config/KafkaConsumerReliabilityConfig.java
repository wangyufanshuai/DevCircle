package io.github.devcircle.common.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Shared at-least-once consumer failure policy.
 *
 * Failed records are retried with exponential backoff and then published to a
 * same-name {@code .DLT} topic. Partition {@code -1} lets Kafka choose a valid
 * partition even when a DLT has fewer partitions than its source topic.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerReliabilityConfig {
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaConsumerErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${devcircle.kafka.retry.initial-interval-ms:1000}") long initialInterval,
            @Value("${devcircle.kafka.retry.multiplier:2.0}") double multiplier,
            @Value("${devcircle.kafka.retry.max-interval-ms:10000}") long maxInterval,
            @Value("${devcircle.kafka.retry.max-attempts:4}") long maxAttempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOff backOff = new ExponentialBackOff(
                Math.max(1, initialInterval), Math.max(1.0, multiplier));
        backOff.setMaxInterval(Math.max(initialInterval, maxInterval));
        backOff.setMaxAttempts(Math.max(1, maxAttempts));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }
}
