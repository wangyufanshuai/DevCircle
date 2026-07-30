package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class FollowChangedConsumer {
    private final ObjectMapper objectMapper;
    private final InteractionConsumerDedup dedup;
    private final FollowSignalStore signalStore;

    FollowChangedConsumer(ObjectMapper objectMapper, InteractionConsumerDedup dedup, FollowSignalStore signalStore) {
        this.objectMapper = objectMapper;
        this.dedup = dedup;
        this.signalStore = signalStore;
    }

    @KafkaListener(topics = EventTopics.FOLLOW_CHANGED, groupId = "dc-recommend-follow")
    void consume(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = required(envelope, "eventId");
        String topic = required(envelope, "type");
        if (!EventTopics.FOLLOW_CHANGED.equals(topic)) throw new IllegalArgumentException("Unexpected follow topic");
        if (!dedup.claim(eventId, topic)) return;
        try {
            JsonNode payload = envelope.path("payload");
            long actorId = payload.path("actorId").asLong(0);
            long receiverId = payload.path("receiverId").asLong(0);
            String status = payload.path("status").asText("");
            if (actorId <= 0 || receiverId <= 0 || actorId == receiverId) {
                throw new IllegalArgumentException("Invalid follow event payload");
            }
            signalStore.apply(eventId, actorId, receiverId, status);
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
