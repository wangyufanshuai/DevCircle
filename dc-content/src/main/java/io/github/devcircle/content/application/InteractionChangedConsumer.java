package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.infrastructure.ContentInteractionProjectionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class InteractionChangedConsumer {
    private final ContentInteractionProjectionRepository projectionRepository;
    private final ObjectMapper objectMapper;

    InteractionChangedConsumer(ContentInteractionProjectionRepository projectionRepository, ObjectMapper objectMapper) {
        this.projectionRepository = projectionRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = EventTopics.INTERACTION_CHANGED, groupId = "dc-content-interaction-projection")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = requiredText(envelope, "eventId");
        String type = requiredText(envelope, "type");
        if (!EventTopics.INTERACTION_CHANGED.equals(type)) {
            throw new IllegalArgumentException("Unexpected event type: " + type);
        }
        JsonNode payload = envelope.path("payload");
        long postId = payload.path("postId").asLong(0);
        String interactionType = payload.path("interactionType").asText("");
        if (postId <= 0 || interactionType.isBlank()) {
            throw new IllegalArgumentException("Interaction event is missing postId or interactionType");
        }
        projectionRepository.apply(eventId, type, postId, interactionType);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Event is missing " + field);
        }
        return value;
    }
}
