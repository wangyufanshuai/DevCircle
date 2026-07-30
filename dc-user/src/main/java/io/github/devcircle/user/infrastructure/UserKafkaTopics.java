package io.github.devcircle.user.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled", havingValue = "true", matchIfMissing = true)
class UserKafkaTopics {
    @Bean
    KafkaAdmin.NewTopics identityTopics() {
        return new KafkaAdmin.NewTopics(
                topic(EventTopics.USER_REGISTERED), deadLetter(EventTopics.USER_REGISTERED),
                topic(EventTopics.FOLLOW_CHANGED), deadLetter(EventTopics.FOLLOW_CHANGED));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    private NewTopic deadLetter(String name) {
        return topic(name + ".DLT");
    }
}
