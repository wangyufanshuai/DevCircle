package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.content.infrastructure.ContentCommentProjectionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
class CommentChangedConsumer {
    private static final Map<String, Set<String>> EVENT_CONTRACTS = Map.of(
            EventTopics.COMMENT_CREATED, Set.of("dc-comment"),
            EventTopics.COMMENT_CHANGED, Set.of("dc-comment"));
    private static final Set<String> ALLOWED_CHANGES = Set.of("CREATED", "DELETED", "HIDDEN");

    private final ContentCommentProjectionRepository projectionRepository;
    private final EventEnvelopeValidator envelopeValidator;

    CommentChangedConsumer(ContentCommentProjectionRepository projectionRepository, ObjectMapper objectMapper) {
        this.projectionRepository = projectionRepository;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
    }

    @KafkaListener(topics = {EventTopics.COMMENT_CREATED, EventTopics.COMMENT_CHANGED},
            groupId = "dc-content-comment-projection")
    void consume(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, EVENT_CONTRACTS);
        JsonNode payload = event.payload();
        long commentId = requiredPositiveLong(payload, "commentId");
        long postId = requiredPositiveLong(payload, "postId");
        String changeType = requiredText(payload, "changeType");
        if (!ALLOWED_CHANGES.contains(changeType)) {
            throw new IllegalArgumentException("Unsupported comment change type: " + changeType);
        }
        if (EventTopics.COMMENT_CREATED.equals(event.type()) && !"CREATED".equals(changeType)) {
            throw new IllegalArgumentException("Legacy comment.created only supports CREATED changes");
        }
        if (!event.aggregateId().equals(Long.toString(commentId))) {
            throw new IllegalArgumentException("Comment aggregateId does not match commentId");
        }
        Long commentCount = optionalNonNegativeLong(payload, "commentCount");
        Long counterVersion = optionalPositiveLong(payload, "counterVersion");
        requirePairedSnapshot(commentCount, counterVersion);
        projectionRepository.apply(event, postId, changeType, counterVersion, commentCount);
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Comment payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Comment payload " + field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private Long optionalNonNegativeLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Comment payload " + field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private Long optionalPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Comment payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private void requirePairedSnapshot(Long count, Long version) {
        if ((count == null) != (version == null)) {
            throw new IllegalArgumentException("Comment counter snapshot requires commentCount and counterVersion");
        }
    }
}
