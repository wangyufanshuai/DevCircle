package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
class InteractionChangedConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACT =
            Map.of(EventTopics.INTERACTION_CHANGED, Set.of("dc-interaction"));
    private static final Set<String> ALLOWED_INTERACTIONS = Set.of("LIKE", "UNLIKE", "FAVORITE", "UNFAVORITE");

    private final EventEnvelopeValidator envelopeValidator;
    private final InteractionConsumerDedup dedup;
    private final InteractionSignalStore signalStore;

    InteractionChangedConsumer(ObjectMapper objectMapper,
                               InteractionConsumerDedup dedup,
                               InteractionSignalStore signalStore) {
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.dedup = dedup;
        this.signalStore = signalStore;
    }

    @KafkaListener(topics = EventTopics.INTERACTION_CHANGED, groupId = "dc-recommend")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACT);
        String eventId = event.eventId().toString();
        String topic = event.type();
        if (!dedup.claim(eventId, topic)) return;
        try {
            JsonNode payload = event.payload();
            long actorId = requiredPositiveLong(payload, "actorId");
            long postId = requiredPositiveLong(payload, "postId");
            String type = requiredText(payload, "interactionType");
            if (!ALLOWED_INTERACTIONS.contains(type)) {
                throw new IllegalArgumentException("Unsupported interaction type: " + type);
            }
            if (!event.aggregateId().equals(Long.toString(postId))) {
                throw new IllegalArgumentException("Interaction aggregateId does not match postId");
            }
            signalStore.apply(eventId, actorId, postId, type);
            dedup.markCompleted(eventId);
        } catch (Exception ex) {
            try { dedup.markFailed(eventId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
            catch (RuntimeException ignored) { ex.addSuppressed(ignored); }
            throw ex;
        }
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
}
