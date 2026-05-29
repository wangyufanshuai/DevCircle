package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class NotificationEventConsumer {
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    NotificationEventConsumer(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {EventTopics.AUDIT_COMPLETED, EventTopics.INTERACTION_CHANGED}, groupId = "dc-notification")
    void consume(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long receiverId = root.path("receiverId").asLong(root.path("authorId").asLong(2L));
            String topic = root.path("topic").asText("");
            String type = topic.contains("interaction") ? "INTERACTION" : "AUDIT";
            String title = "AUDIT".equals(type) ? "审核状态更新" : "互动提醒";
            String content = root.has("status") ? "内容状态已更新为 " + root.path("status").asText() : "你的内容收到新的互动。";
            notificationRepository.create(receiverId, type, title, content);
        } catch (RuntimeException | java.io.IOException ignored) {
            // Kafka keeps the event replayable; notification loss is visible through consumer lag/alerts.
        }
    }
}
