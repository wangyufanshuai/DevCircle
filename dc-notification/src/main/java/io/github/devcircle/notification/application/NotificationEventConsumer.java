package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class NotificationEventConsumer {
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final NotificationTargetResolver targetResolver;

    @Autowired
    NotificationEventConsumer(NotificationRepository notificationRepository, ObjectMapper objectMapper,
                              NotificationTargetResolver targetResolver) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        this.targetResolver = targetResolver;
    }

    NotificationEventConsumer(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this(notificationRepository, objectMapper, null);
    }

    @KafkaListener(topics = {EventTopics.AUDIT_COMPLETED, EventTopics.CONTENT_REJECTED,
            EventTopics.APPEAL_RESOLVED, EventTopics.INTERACTION_CHANGED}, groupId = "dc-notification")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = requiredText(envelope, "eventId");
        String topic = requiredText(envelope, "type");
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Event payload must be an object");
        }
        boolean interaction = EventTopics.INTERACTION_CHANGED.equals(topic);
        boolean appeal = EventTopics.APPEAL_RESOLVED.equals(topic);
        long receiverId = payload.path("receiverId").asLong(0);
        long targetIdValue = payload.path("targetId").asLong(payload.path("postId").asLong(0));
        if (interaction && receiverId <= 0 && targetResolver != null && targetIdValue > 0) {
            receiverId = targetResolver.postAuthorId(targetIdValue);
        }
        if (receiverId <= 0) {
            throw new IllegalArgumentException("Notification event is missing receiverId");
        }
        String type = interaction ? "INTERACTION" : "AUDIT";
        String title = interaction ? "新的内容互动" : appeal ? "申诉处理结果" : "投稿审核状态更新";
        String status = payload.path("status").asText("UNKNOWN");
        String content = interaction ? "你的内容收到了一次新的互动"
                : appeal ? ("PENDING".equals(status) ? "你的申诉已受理，投稿已进入人工复审队列" : "你的申诉已处理，请查看审核时间线")
                : "你的投稿状态已更新为 " + status;
        long actorId = payload.path("actorId").asLong(0);
        Long targetId = targetIdValue > 0 ? targetIdValue : null;
        String targetType = payload.path("targetType").asText(targetId == null ? "" : "POST");
        String targetRoute = payload.path("targetRoute").asText(targetId == null ? "" : "/posts/" + targetId);
        notificationRepository.createOnce(eventId, topic, receiverId, actorId > 0 ? actorId : null, type, title,
                content, targetType, targetId, targetRoute);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Event is missing " + field);
        }
        return value;
    }
}
