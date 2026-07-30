package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractionChangedConsumerTest {
    private final InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
    private final InteractionSignalStore signals = mock(InteractionSignalStore.class);
    private final InteractionChangedConsumer consumer = new InteractionChangedConsumer(new ObjectMapper(), dedup, signals);

    @Test
    void claimsEventBeforeApplyingSignalsAndMarksCompletion() throws Exception {
        when(dedup.claim("evt-1", "interaction.changed")).thenReturn(true);

        consumer.consume("""
                {"eventId":"evt-1","type":"interaction.changed","payload":{"actorId":7,"postId":9001,"interactionType":"LIKE"}}
                """);

        verify(signals).apply("evt-1", 7L, 9001L, "LIKE");
        verify(dedup).markCompleted("evt-1");
    }

    @Test
    void failedSignalApplicationIsMarkedAndPropagatedForKafkaRetry() throws Exception {
        when(dedup.claim("evt-2", "interaction.changed")).thenReturn(true);
        RuntimeException failure = new IllegalStateException("redis down");
        org.mockito.Mockito.doThrow(failure).when(signals).apply("evt-2", 7L, 9001L, "LIKE");

        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"evt-2","type":"interaction.changed","payload":{"actorId":7,"postId":9001,"interactionType":"LIKE"}}
                """)).isSameAs(failure);
        verify(dedup).markFailed("evt-2", "redis down");
    }

    @Test
    void duplicateCompletedEventDoesNotApplySignalsTwice() throws Exception {
        when(dedup.claim("evt-3", "interaction.changed")).thenReturn(false);

        consumer.consume("""
                {"eventId":"evt-3","type":"interaction.changed","payload":{"actorId":7,"postId":9001,"interactionType":"LIKE"}}
                """);

        org.mockito.Mockito.verifyNoInteractions(signals);
    }
}
