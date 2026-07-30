package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled", havingValue = "true", matchIfMissing = true)
class ContentCommentTopics {
    @Bean
    NewTopic commentCreatedTopic() {
        return topic(EventTopics.COMMENT_CREATED);
    }

    @Bean
    NewTopic commentChangedTopic() {
        return topic(EventTopics.COMMENT_CHANGED);
    }

    @Bean
    NewTopic commentCreatedDeadLetterTopic() {
        return topic(EventTopics.COMMENT_CREATED + ".DLT");
    }

    @Bean
    NewTopic commentChangedDeadLetterTopic() {
        return topic(EventTopics.COMMENT_CHANGED + ".DLT");
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
