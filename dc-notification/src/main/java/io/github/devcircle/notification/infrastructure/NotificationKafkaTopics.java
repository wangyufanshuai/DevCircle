package io.github.devcircle.notification.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.notification-runtime", name = "topic-admin-enabled",
        havingValue = "true", matchIfMissing = true)
class NotificationKafkaTopics {
    @Bean
    @ConditionalOnProperty(prefix = "devcircle.kafka.topics", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    KafkaAdmin.NewTopics notificationTopics() {
        return new KafkaAdmin.NewTopics(
                topic(EventTopics.AUDIT_COMPLETED), deadLetter(EventTopics.AUDIT_COMPLETED),
                topic(EventTopics.CONTENT_REJECTED), deadLetter(EventTopics.CONTENT_REJECTED),
                topic(EventTopics.APPEAL_RESOLVED), deadLetter(EventTopics.APPEAL_RESOLVED),
                topic(EventTopics.INTERACTION_CHANGED), deadLetter(EventTopics.INTERACTION_CHANGED),
                topic(EventTopics.FOLLOW_CHANGED), deadLetter(EventTopics.FOLLOW_CHANGED));
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    private NewTopic deadLetter(String name) {
        return topic(name + ".DLT");
    }
}
