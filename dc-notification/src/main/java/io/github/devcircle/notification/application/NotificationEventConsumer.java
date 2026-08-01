package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
class NotificationEventConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACTS = Map.of(
            EventTopics.AUDIT_COMPLETED, Set.of("dc-admin"),
            EventTopics.CONTENT_REJECTED, Set.of("dc-admin"),
            EventTopics.APPEAL_RESOLVED, Set.of("dc-admin"),
            EventTopics.INTERACTION_CHANGED, Set.of("dc-interaction"),
            EventTopics.FOLLOW_CHANGED, Set.of("dc-user"));

    private final NotificationRepository notificationRepository;
    private final EventEnvelopeValidator envelopeValidator;
    private final NotificationTargetResolver targetResolver;

    @Autowired
    NotificationEventConsumer(NotificationRepository notificationRepository, ObjectMapper objectMapper,
                              NotificationTargetResolver targetResolver) {
        this.notificationRepository = notificationRepository;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.targetResolver = targetResolver;
    }

    NotificationEventConsumer(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this(notificationRepository, objectMapper, null);
    }

    @KafkaListener(topics = {EventTopics.AUDIT_COMPLETED, EventTopics.CONTENT_REJECTED,
            EventTopics.APPEAL_RESOLVED, EventTopics.INTERACTION_CHANGED,
            EventTopics.FOLLOW_CHANGED}, groupId = "dc-notification")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACTS);
        String eventId = event.eventId().toString();
        String topic = event.type();
        JsonNode payload = event.payload();
        boolean interaction = EventTopics.INTERACTION_CHANGED.equals(topic);
        boolean appeal = EventTopics.APPEAL_RESOLVED.equals(topic);
        boolean follow = EventTopics.FOLLOW_CHANGED.equals(topic);
        long receiverId = optionalPositiveLong(payload, "receiverId");
        long actorId = optionalPositiveLong(payload, "actorId");
        long targetIdValue = optionalPositiveLong(payload, "targetId");
        if (targetIdValue == 0) targetIdValue = optionalPositiveLong(payload, "postId");
        if (follow && receiverId > 0 && !event.aggregateId().equals(Long.toString(receiverId))) {
            throw new IllegalArgumentException("Follow aggregateId does not match receiverId");
        }
        if (!follow && targetIdValue > 0 && !event.aggregateId().equals(Long.toString(targetIdValue))) {
            throw new IllegalArgumentException("Notification aggregateId does not match targetId");
        }
        if (interaction) {
            if (actorId <= 0) {
                throw new IllegalArgumentException("Interaction notification event is missing actorId");
            }
            String interactionType = optionalText(payload, "interactionType", "");
            if ("UNLIKE".equals(interactionType) || "UNFAVORITE".equals(interactionType)) {
                notificationRepository.acknowledgeOnce(eventId, topic);
                return;
            }
            if (!"LIKE".equals(interactionType) && !"FAVORITE".equals(interactionType)) {
                throw new IllegalArgumentException("Unsupported interaction type: " + interactionType);
            }
        }
        if (interaction && receiverId <= 0 && targetResolver != null && targetIdValue > 0) {
            receiverId = targetResolver.postAuthorId(targetIdValue);
        }
        if (receiverId <= 0) {
            throw new IllegalArgumentException("Notification event is missing receiverId");
        }
        String status = optionalText(payload, "status", "UNKNOWN");
        if (follow && actorId <= 0) {
            throw new IllegalArgumentException("Follow notification event is missing actorId");
        }
        if (follow && !"FOLLOWED".equals(status)) {
            if (!"UNFOLLOWED".equals(status)) {
                throw new IllegalArgumentException("Unsupported follow status: " + status);
            }
            notificationRepository.acknowledgeOnce(eventId, topic);
            return;
        }
        if ((follow || interaction) && actorId == receiverId) {
            notificationRepository.acknowledgeOnce(eventId, topic);
            return;
        }
        if (!interaction && !follow && ("UNKNOWN".equals(status) || status.isBlank())) {
            throw new IllegalArgumentException("Audit notification event is missing status");
        }
        String type = follow ? "FOLLOW" : interaction ? "INTERACTION" : "AUDIT";
        String title = follow ? "新的关注者" : interaction ? "新的内容互动" : appeal ? "申诉处理结果" : "投稿审核状态更新";
        String actorUsername = optionalText(payload, "actorUsername", "一位读者");
        String content = follow ? actorUsername + " 关注了你"
                : interaction ? "你的内容收到了一次新的互动"
                : appeal ? ("PENDING".equals(status) ? "你的申诉已受理，投稿已进入人工复审队列" : "你的申诉已处理，请查看审核时间线")
                : "你的投稿状态已更新为 " + status;
        Long targetId = targetIdValue > 0 ? targetIdValue : null;
        String targetType = optionalText(payload, "targetType", targetId == null ? "" : follow ? "USER" : "POST");
        String targetRoute = optionalText(payload, "targetRoute", targetId == null ? "" : follow ? "/me" : "/posts/" + targetId);
        notificationRepository.createOnce(eventId, topic, receiverId, actorId > 0 ? actorId : null, type, title,
                content, targetType, targetId, targetRoute);
    }

    private long optionalPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Notification payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String optionalText(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Notification payload " + field + " must be a string");
        }
        return value.textValue();
    }
}
