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
        consumer.consume("""
                {"eventId":"e7106d33-45e2-4e26-9a25-757488b57f98","type":"interaction.changed",\
                 "version":1,"payload":{"postId":1001,"actorId":7,"interactionType":"LIKE"}}
                """);

        verify(repository).apply("e7106d33-45e2-4e26-9a25-757488b57f98",
                EventTopics.INTERACTION_CHANGED, 1001L, "LIKE");
    }

    @Test
    void rejectsMalformedEnvelopeWithoutClaimingDeduplication() {
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"e7106d33-45e2-4e26-9a25-757488b57f98","type":"interaction.changed","payload":{}}
                """))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }
}
