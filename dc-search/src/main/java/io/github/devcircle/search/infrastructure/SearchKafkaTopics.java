package io.github.devcircle.search.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled", havingValue = "true", matchIfMissing = true)
class SearchKafkaTopics {
    @Bean
    KafkaAdmin.NewTopics searchTopics() {
        return new KafkaAdmin.NewTopics(
                topic(EventTopics.POST_CREATED), deadLetter(EventTopics.POST_CREATED),
                topic(EventTopics.POST_UPDATED), deadLetter(EventTopics.POST_UPDATED),
                topic(EventTopics.AUDIT_COMPLETED), deadLetter(EventTopics.AUDIT_COMPLETED),
                topic(EventTopics.CONTENT_REJECTED), deadLetter(EventTopics.CONTENT_REJECTED),
                topic(EventTopics.POST_DELETED), deadLetter(EventTopics.POST_DELETED));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    private NewTopic deadLetter(String name) {
        return topic(name + ".DLT");
    }
}
