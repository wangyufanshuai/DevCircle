package io.github.devcircle.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.GlobalExceptionHandler;
import io.github.devcircle.common.event.ReliableOutboxWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommonWebConfig {
    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    TraceContextFilter traceContextFilter() {
        return new TraceContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnClass({JdbcClient.class, KafkaTemplate.class})
    @ConditionalOnBean({JdbcClient.class, KafkaTemplate.class})
    @ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker reliableOutboxWorker(JdbcClient jdbcClient, KafkaTemplate<String, String> kafkaTemplate) {
        return new ReliableOutboxWorker(jdbcClient, kafkaTemplate, 50, 8);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
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
