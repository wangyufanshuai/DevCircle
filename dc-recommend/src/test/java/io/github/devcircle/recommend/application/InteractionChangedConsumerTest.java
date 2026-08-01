package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InteractionChangedConsumerTest {
    private static final String EVENT_ID_1 = "ec7f4559-9b8c-46ba-855d-fb542f2ca728";
    private static final String EVENT_ID_2 = "1379f1d9-9f47-4b69-b324-e6eebd44fae2";
    private static final String EVENT_ID_3 = "9be49da2-4f44-46b3-a251-57b240ea3a84";
    private final InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
    private final InteractionSignalStore signals = mock(InteractionSignalStore.class);
    private final InteractionChangedConsumer consumer = new InteractionChangedConsumer(new ObjectMapper(), dedup, signals);

    @Test
    void claimsEventBeforeApplyingSignalsAndMarksCompletion() throws Exception {
        when(dedup.claim(EVENT_ID_1, "interaction.changed")).thenReturn(true);

        consumer.consume(event(EVENT_ID_1, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """));

        verify(signals).apply(EVENT_ID_1, 7L, 9001L, "LIKE");
        verify(dedup).markCompleted(EVENT_ID_1);
    }

    @Test
    void failedSignalApplicationIsMarkedAndPropagatedForKafkaRetry() throws Exception {
        when(dedup.claim(EVENT_ID_2, "interaction.changed")).thenReturn(true);
        RuntimeException failure = new IllegalStateException("redis down");
        org.mockito.Mockito.doThrow(failure).when(signals).apply(EVENT_ID_2, 7L, 9001L, "LIKE");

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_2, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isSameAs(failure);
        verify(dedup).markFailed(EVENT_ID_2, "redis down");
    }

    @Test
    void duplicateCompletedEventDoesNotApplySignalsTwice() throws Exception {
        when(dedup.claim(EVENT_ID_3, "interaction.changed")).thenReturn(false);

        consumer.consume(event(EVENT_ID_3, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """));

        verifyNoInteractions(signals);
    }

    @Test
    void malformedEnvelopeIsRejectedBeforeTheDedupRecordIsClaimed() {
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"evt-1","type":"interaction.changed","version":1,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"dc-interaction",
                 "aggregateId":"9001","payload":{"actorId":7,"postId":9001,"interactionType":"LIKE"}}
                """)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical UUID");

        verifyNoInteractions(dedup, signals);
    }

    @Test
    void aggregateIdentityMismatchIsMarkedForRetryAndNeverProjected() throws Exception {
        when(dedup.claim(EVENT_ID_1, "interaction.changed")).thenReturn(true);

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_1, "9002", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interaction aggregateId does not match postId");

        verify(dedup).markFailed(EVENT_ID_1, "Interaction aggregateId does not match postId");
        verifyNoInteractions(signals);
    }

    private String event(String eventId, String aggregateId, String payload) {
        return """
                {"eventId":"%s","type":"interaction.changed","version":1,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"dc-interaction",
                 "aggregateId":"%s","payload":%s}
                """.formatted(eventId, aggregateId, payload);
    }
}
