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
class FollowChangedConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACT =
            Map.of(EventTopics.FOLLOW_CHANGED, Set.of("dc-user"));
    private static final Set<String> ALLOWED_STATUSES = Set.of("FOLLOWED", "UNFOLLOWED");

    private final EventEnvelopeValidator envelopeValidator;
    private final InteractionConsumerDedup dedup;
    private final FollowSignalStore signalStore;

    FollowChangedConsumer(ObjectMapper objectMapper, InteractionConsumerDedup dedup, FollowSignalStore signalStore) {
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.dedup = dedup;
        this.signalStore = signalStore;
    }

    @KafkaListener(topics = EventTopics.FOLLOW_CHANGED, groupId = "dc-recommend-follow")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACT);
        String eventId = event.eventId().toString();
        String topic = event.type();
        if (!dedup.claim(eventId, topic)) return;
        try {
            JsonNode payload = event.payload();
            long actorId = requiredPositiveLong(payload, "actorId");
            long receiverId = requiredPositiveLong(payload, "receiverId");
            String status = requiredText(payload, "status");
            if (actorId == receiverId) {
                throw new IllegalArgumentException("Invalid follow event payload");
            }
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Unsupported follow status: " + status);
            }
            if (!event.aggregateId().equals(Long.toString(receiverId))) {
                throw new IllegalArgumentException("Follow aggregateId does not match receiverId");
            }
            signalStore.apply(eventId, actorId, receiverId, status);
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
            throw new IllegalArgumentException("Follow payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Follow payload " + field + " must be a non-blank string");
        }
        return value.textValue();
    }
}
