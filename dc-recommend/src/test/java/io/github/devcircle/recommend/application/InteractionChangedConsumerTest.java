package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.ValidatedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-1"));

        consumer.consume(event(EVENT_ID_1, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """));

        verify(signals).apply(EVENT_ID_1, 7L, 9001L, "LIKE");
        verify(dedup).markCompleted(EVENT_ID_1, "lease-1");
    }

    @Test
    void failedSignalApplicationIsMarkedAndPropagatedForKafkaRetry() throws Exception {
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-2"));
        RuntimeException failure = new IllegalStateException("redis down");
        org.mockito.Mockito.doThrow(failure).when(signals).apply(EVENT_ID_2, 7L, 9001L, "LIKE");

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_2, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isSameAs(failure);
        verify(dedup).markFailed(EVENT_ID_2, "lease-2", "redis down");
    }

    @Test
    void duplicateCompletedEventDoesNotApplySignalsTwice() throws Exception {
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.duplicate());

        consumer.consume(event(EVENT_ID_3, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """));

        verifyNoInteractions(signals);
    }

    @Test
    void activeProcessingClaimIsPropagatedForKafkaRetry() {
        JdbcInteractionConsumerDedup.EventProcessingInProgressException failure =
                new JdbcInteractionConsumerDedup.EventProcessingInProgressException("event is still processing");
        when(dedup.claim(any(ValidatedEvent.class))).thenThrow(failure);

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_3, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isSameAs(failure);

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
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-1"));

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_1, "9002", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interaction aggregateId does not match postId");

        verify(dedup).markFailed(EVENT_ID_1, "lease-1", "Interaction aggregateId does not match postId");
        verifyNoInteractions(signals);
    }

    @Test
    void invalidPayloadFieldsAndInteractionTypeAreMarkedForRetry() {
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-1"));

        assertRejected("{\"actorId\":\"7\",\"postId\":9001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"postId\":9001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"actorId\":999999999999999999999999999999,\"postId\":9001,\"interactionType\":\"LIKE\"}",
                "Interaction payload actorId must be a positive integer");
        assertRejected("{\"actorId\":7,\"postId\":0,\"interactionType\":\"LIKE\"}",
                "Interaction payload postId must be a positive integer");
        assertRejected("{\"actorId\":7,\"postId\":9001,\"interactionType\":7}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":9001}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":9001,\"interactionType\":\" \"}",
                "Interaction payload interactionType must be a non-blank string");
        assertRejected("{\"actorId\":7,\"postId\":9001,\"interactionType\":\"SHARE\"}",
                "Unsupported interaction type: SHARE");

        verifyNoInteractions(signals);
    }

    @Test
    void dedupFailureIsSuppressedWithoutHidingTheProjectionFailure() throws Exception {
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-1"));
        RuntimeException projectionFailure = new RuntimeException();
        RuntimeException stateFailure = new IllegalStateException("dedup unavailable");
        doThrow(projectionFailure).when(signals).apply(EVENT_ID_1, 7L, 9001L, "LIKE");
        doThrow(stateFailure).when(dedup).markFailed(EVENT_ID_1, "lease-1", "RuntimeException");

        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_1, "9001", """
                {"actorId":7,"postId":9001,"interactionType":"LIKE"}
                """))).isSameAs(projectionFailure);
        assertThat(projectionFailure.getSuppressed()).containsExactly(stateFailure);
    }

    private void assertRejected(String payload, String message) {
        assertThatThrownBy(() -> consumer.consume(event(EVENT_ID_1, "9001", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private String event(String eventId, String aggregateId, String payload) {
        return """
                {"eventId":"%s","type":"interaction.changed","version":1,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"dc-interaction",
                 "aggregateId":"%s","payload":%s}
                """.formatted(eventId, aggregateId, payload);
    }
}
