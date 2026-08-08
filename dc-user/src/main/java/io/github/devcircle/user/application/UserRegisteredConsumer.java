package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
class UserRegisteredConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACT =
            Map.of(EventTopics.USER_REGISTERED, Set.of("dc-auth"));

    private final EventEnvelopeValidator envelopeValidator;
    private final UserGraphRepository repository;

    UserRegisteredConsumer(ObjectMapper objectMapper, UserGraphRepository repository) {
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.repository = repository;
    }

    @KafkaListener(topics = EventTopics.USER_REGISTERED, groupId = "dc-user-profile")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACT);
        JsonNode payload = event.payload();
        JsonNode userIdNode = payload.get("userId");
        JsonNode displayNameNode = payload.get("displayName");
        if (userIdNode == null || !userIdNode.isIntegralNumber() || !userIdNode.canConvertToLong()
                || userIdNode.longValue() <= 0 || displayNameNode == null || !displayNameNode.isTextual()) {
            throw new IllegalArgumentException("Invalid user registration event payload");
        }
        long userId = userIdNode.longValue();
        String displayName = displayNameNode.textValue();
        if (displayName.isBlank() || displayName.length() > 64 || !displayName.equals(displayName.strip())) {
            throw new IllegalArgumentException("Invalid user registration event payload");
        }
        if (!event.aggregateId().equals(Long.toString(userId))) {
            throw new IllegalArgumentException("User aggregateId does not match userId");
        }
        repository.synchronizeProfile(event, userId, displayName);
    }
}
