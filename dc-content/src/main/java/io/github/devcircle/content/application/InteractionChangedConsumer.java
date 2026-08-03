package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.content.infrastructure.ContentInteractionProjectionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
class InteractionChangedConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACT =
            Map.of(EventTopics.INTERACTION_CHANGED, Set.of("dc-interaction"));
    private static final Set<String> ALLOWED_INTERACTIONS =
            Set.of("LIKE", "UNLIKE", "FAVORITE", "UNFAVORITE");

    private final ContentInteractionProjectionRepository projectionRepository;
    private final EventEnvelopeValidator envelopeValidator;

    InteractionChangedConsumer(ContentInteractionProjectionRepository projectionRepository, ObjectMapper objectMapper) {
        this.projectionRepository = projectionRepository;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
    }

    @KafkaListener(topics = EventTopics.INTERACTION_CHANGED, groupId = "dc-content-interaction-projection")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACT);
        JsonNode payload = event.payload();
        long actorId = requiredPositiveLong(payload, "actorId");
        long postId = requiredPositiveLong(payload, "postId");
        String interactionType = requiredText(payload, "interactionType");
        if (!ALLOWED_INTERACTIONS.contains(interactionType)) {
            throw new IllegalArgumentException("Unsupported interaction type: " + interactionType);
        }
        if (!event.aggregateId().equals(Long.toString(postId))) {
            throw new IllegalArgumentException("Interaction aggregateId does not match postId");
        }
        Long likeCount = optionalNonNegativeLong(payload, "likeCount");
        Long counterVersion = optionalPositiveLong(payload, "counterVersion");
        requirePairedSnapshot(likeCount, counterVersion);
        projectionRepository.apply(event, postId, interactionType, counterVersion, likeCount);
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Interaction payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Interaction payload " + field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private Long optionalNonNegativeLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Interaction payload " + field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private Long optionalPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Interaction payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private void requirePairedSnapshot(Long count, Long version) {
        if ((count == null) != (version == null)) {
            throw new IllegalArgumentException("Interaction counter snapshot requires likeCount and counterVersion");
        }
    }
}
