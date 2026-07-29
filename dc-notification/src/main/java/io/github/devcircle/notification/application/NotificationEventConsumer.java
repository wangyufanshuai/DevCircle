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

    @KafkaListener(topics = {EventTopics.AUDIT_COMPLETED, EventTopics.CONTENT_REJECTED, EventTopics.INTERACTION_CHANGED}, groupId = "dc-notification")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = requiredText(envelope, "eventId");
        String topic = requiredText(envelope, "type");
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Event payload must be an object");
        }
        long receiverId = payload.path("receiverId").asLong(0);
        if (receiverId <= 0) {
            throw new IllegalArgumentException("Notification event is missing receiverId");
        }
        boolean interaction = EventTopics.INTERACTION_CHANGED.equals(topic);
        String type = interaction ? "INTERACTION" : "AUDIT";
        String title = interaction ? "新的内容互动" : "投稿审核状态更新";
        String content = interaction
                ? "你的内容收到了一次新的互动"
                : "你的投稿状态已更新为 " + payload.path("status").asText("UNKNOWN");
        notificationRepository.createOnce(eventId, topic, receiverId, type, title, content);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Event is missing " + field);
        }
        return value;
    }
}
