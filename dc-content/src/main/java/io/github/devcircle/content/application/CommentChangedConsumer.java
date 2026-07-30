package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.infrastructure.ContentCommentProjectionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class CommentChangedConsumer {
    private final ContentCommentProjectionRepository projectionRepository;
    private final ObjectMapper objectMapper;

    CommentChangedConsumer(ContentCommentProjectionRepository projectionRepository, ObjectMapper objectMapper) {
        this.projectionRepository = projectionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {EventTopics.COMMENT_CREATED, EventTopics.COMMENT_CHANGED},
            groupId = "dc-content-comment-projection")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = requiredText(envelope, "eventId");
        String topic = requiredText(envelope, "type");
        if (!EventTopics.COMMENT_CREATED.equals(topic) && !EventTopics.COMMENT_CHANGED.equals(topic)) {
            throw new IllegalArgumentException("Unexpected comment event type: " + topic);
        }
        JsonNode payload = envelope.path("payload");
        long postId = payload.path("postId").asLong(0);
        String changeType = payload.path("changeType").asText("");
        if (postId <= 0 || changeType.isBlank()) {
            throw new IllegalArgumentException("Comment event is missing postId or changeType");
        }
        projectionRepository.apply(eventId, topic, postId, changeType);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Event is missing " + field);
        return value;
    }
}
