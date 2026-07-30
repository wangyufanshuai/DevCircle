package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class InteractionChangedConsumer {
    private final ObjectMapper objectMapper;
    private final InteractionConsumerDedup dedup;
    private final InteractionSignalStore signalStore;

    InteractionChangedConsumer(ObjectMapper objectMapper,
                               InteractionConsumerDedup dedup,
                               InteractionSignalStore signalStore) {
        this.objectMapper = objectMapper;
        this.dedup = dedup;
        this.signalStore = signalStore;
    }

    @KafkaListener(topics = EventTopics.INTERACTION_CHANGED, groupId = "dc-recommend")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = required(envelope, "eventId");
        String topic = required(envelope, "type");
        if (!EventTopics.INTERACTION_CHANGED.equals(topic)) throw new IllegalArgumentException("Unexpected interaction topic");
        if (!dedup.claim(eventId, topic)) return;
        try {
            JsonNode payload = envelope.path("payload");
            long actorId = payload.path("actorId").asLong(0);
            long postId = payload.path("postId").asLong(0);
            String type = payload.path("interactionType").asText("");
            if (actorId <= 0 || postId <= 0 || type.isBlank()) throw new IllegalArgumentException("Invalid interaction event payload");
            signalStore.apply(eventId, actorId, postId, type);
            dedup.markCompleted(eventId);
        } catch (Exception ex) {
            try { dedup.markFailed(eventId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
            catch (RuntimeException ignored) { ex.addSuppressed(ignored); }
            throw ex;
        }
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Event is missing " + field);
        return value;
    }
}
