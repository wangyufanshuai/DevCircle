package io.github.devcircle.recommend.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled", havingValue = "true", matchIfMissing = true)
class RecommendKafkaTopics {
    @Bean
    KafkaAdmin.NewTopics recommendTopics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(EventTopics.INTERACTION_CHANGED).partitions(1).replicas(1).build(),
                TopicBuilder.name(EventTopics.INTERACTION_CHANGED + ".DLT").partitions(1).replicas(1).build());
    }
}
