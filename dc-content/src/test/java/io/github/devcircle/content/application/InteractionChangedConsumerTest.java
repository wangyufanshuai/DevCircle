package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.infrastructure.ContentInteractionProjectionRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InteractionChangedConsumerTest {
    private final ContentInteractionProjectionRepository repository = mock(ContentInteractionProjectionRepository.class);
    private final InteractionChangedConsumer consumer = new InteractionChangedConsumer(repository, new ObjectMapper());

    @Test
    void projectsValidInteractionEnvelope() throws Exception {
        consumer.consume(event("1001", "dc-interaction", """
                {"postId":1001,"actorId":7,"interactionType":"LIKE"}
                """));

        verify(repository).apply("e7106d33-45e2-4e26-9a25-757488b57f98",
                EventTopics.INTERACTION_CHANGED, 1001L, "LIKE");
    }

    @Test
    void rejectsMalformedEnvelopeWithoutClaimingDeduplication() {
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"e7106d33-45e2-4e26-9a25-757488b57f98","type":"interaction.changed","payload":{}}
                """)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsProducerAndAggregateIdentityMismatchBeforeProjection() {
        assertThatThrownBy(() -> consumer.consume(event("1001", "dc-user", """
                {"postId":1001,"actorId":7,"interactionType":"LIKE"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event producer dc-user for type interaction.changed");
        assertThatThrownBy(() -> consumer.consume(event("1002", "dc-interaction", """
                {"postId":1001,"actorId":7,"interactionType":"LIKE"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interaction aggregateId does not match postId");

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedPayloadFieldsBeforeProjection() {
        assertRejected("{\"actorId\":\"7\",\"postId\":1001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"actorId\":0,\"postId\":1001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"actorId\":999999999999999999999999999999,\"postId\":1001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"actorId\":7,\"postId\":\"1001\",\"interactionType\":\"LIKE\"}",
                "Interaction payload postId must be a positive integer");
        assertRejected("{\"actorId\":7,\"postId\":1001}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":1001,\"interactionType\":7}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":1001,\"interactionType\":\" \"}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":1001,\"interactionType\":\"SHARE\"}",
                "Unsupported interaction type: SHARE");

        verifyNoInteractions(repository);
    }

    private void assertRejected(String payload, String message) {
        assertThatThrownBy(() -> consumer.consume(event("1001", "dc-interaction", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private String event(String aggregateId, String producer, String payload) {
        return """
                {"eventId":"e7106d33-45e2-4e26-9a25-757488b57f98","type":"interaction.changed",
                 "version":1,"occurredAt":"2026-08-01T10:15:30Z","producer":"%s",
                 "aggregateId":"%s","payload":%s}
                """.formatted(producer, aggregateId, payload);
    }
}
