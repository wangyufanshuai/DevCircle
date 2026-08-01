package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(name = "kafkaListenerContainerFactory")
class UserRegisteredConsumer {
    private final ObjectMapper objectMapper;
    private final UserGraphRepository repository;

    UserRegisteredConsumer(ObjectMapper objectMapper, UserGraphRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @KafkaListener(topics = EventTopics.USER_REGISTERED, groupId = "dc-user-profile")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = required(envelope, "eventId");
        String type = required(envelope, "type");
        if (!EventTopics.USER_REGISTERED.equals(type)) {
            throw new IllegalArgumentException("Unexpected user event type");
        }
        JsonNode payload = envelope.path("payload");
        long userId = payload.path("userId").asLong(0);
        String displayName = payload.path("displayName").asText("").trim();
        if (userId <= 0 || displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalArgumentException("Invalid user registration event payload");
        }
        repository.synchronizeProfile(eventId, type, userId, displayName);
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Event is missing " + field);
        return value;
    }
}
