package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled", havingValue = "true", matchIfMissing = true)
class ContentInteractionTopics {
    @Bean
    NewTopic interactionChangedTopic() {
        return TopicBuilder.name(EventTopics.INTERACTION_CHANGED).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic interactionChangedDeadLetterTopic() {
        return TopicBuilder.name(EventTopics.INTERACTION_CHANGED + ".DLT").partitions(1).replicas(1).build();
    }
}
