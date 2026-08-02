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

class FollowChangedConsumerTest {
    private static final String EVENT_ID = "650232a0-1e88-4c53-b2dd-f1c2169af63d";

    @Test
    void appliesStableAuthorIdentityToTheFollowProjectionAfterClaimingTheEvent() throws Exception {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-follow"));

        consumer.consume(event("7", """
                {"actorId":2,"receiverId":7,"receiverUsername":"林墨","status":"FOLLOWED"}
                """));

        verify(signals).apply(EVENT_ID, 2L, 7L, "FOLLOWED");
        verify(dedup).markCompleted(EVENT_ID, "lease-follow");
    }

    @Test
    void unsupportedEnvelopeVersionIsRejectedBeforeDeduplication() {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);

        assertThatThrownBy(() -> consumer.consume(event(2, "dc-user", "7", """
                {"actorId":2,"receiverId":7,"status":"FOLLOWED"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event version: 2");

        verifyNoInteractions(dedup, signals);
    }

    @Test
    void aggregateIdentityMismatchIsMarkedForRetryAndNeverProjected() throws Exception {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-follow"));

        assertThatThrownBy(() -> consumer.consume(event("99", """
                {"actorId":2,"receiverId":7,"status":"FOLLOWED"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Follow aggregateId does not match receiverId");

        verify(dedup).markFailed(EVENT_ID, "lease-follow", "Follow aggregateId does not match receiverId");
        verifyNoInteractions(signals);
    }

    @Test
    void duplicateEventDoesNotProjectAgain() throws Exception {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.duplicate());

        consumer.consume(event("7", """
                {"actorId":2,"receiverId":7,"status":"FOLLOWED"}
                """));

        verifyNoInteractions(signals);
    }

    @Test
    void invalidPayloadIdentityAndStatusAreMarkedForRetry() {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-follow"));

        assertRejected(consumer, "{\"actorId\":\"2\",\"receiverId\":7,\"status\":\"FOLLOWED\"}",
                "Follow payload actorId must be a positive integer");
        assertRejected(consumer, "{\"actorId\":0,\"receiverId\":7,\"status\":\"FOLLOWED\"}",
                "Follow payload actorId must be a positive integer");
        assertRejected(consumer, "{\"actorId\":999999999999999999999999999999,\"receiverId\":7,\"status\":\"FOLLOWED\"}",
                "Follow payload actorId must be a positive integer");
        assertRejected(consumer, "{\"actorId\":2,\"status\":\"FOLLOWED\"}",
                "Follow payload receiverId must be a positive integer");
        assertRejected(consumer, "{\"actorId\":7,\"receiverId\":7,\"status\":\"FOLLOWED\"}",
                "Invalid follow event payload");
        assertRejected(consumer, "{\"actorId\":2,\"receiverId\":7,\"status\":\"PENDING\"}",
                "Unsupported follow status: PENDING");
        assertRejected(consumer, "{\"actorId\":2,\"receiverId\":7,\"status\":7}",
                "Follow payload status must be a non-blank string");
        assertRejected(consumer, "{\"actorId\":2,\"receiverId\":7}",
                "Follow payload status must be a non-blank string");
        assertRejected(consumer, "{\"actorId\":2,\"receiverId\":7,\"status\":\" \"}",
                "Follow payload status must be a non-blank string");

        verifyNoInteractions(signals);
    }

    @Test
    void dedupFailureIsSuppressedWithoutHidingTheProjectionFailure() throws Exception {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim(any(ValidatedEvent.class))).thenReturn(InteractionConsumerDedup.ClaimResult.acquired("lease-follow"));
        RuntimeException projectionFailure = new RuntimeException();
        RuntimeException stateFailure = new IllegalStateException("dedup unavailable");
        doThrow(projectionFailure).when(signals).apply(EVENT_ID, 2L, 7L, "FOLLOWED");
        doThrow(stateFailure).when(dedup).markFailed(EVENT_ID, "lease-follow", "RuntimeException");

        assertThatThrownBy(() -> consumer.consume(event("7", """
                {"actorId":2,"receiverId":7,"status":"FOLLOWED"}
                """))).isSameAs(projectionFailure);
        assertThat(projectionFailure.getSuppressed()).containsExactly(stateFailure);
    }

    private void assertRejected(FollowChangedConsumer consumer, String payload, String message) {
        assertThatThrownBy(() -> consumer.consume(event("7", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private String event(String aggregateId, String payload) {
        return event(1, "dc-user", aggregateId, payload);
    }

    private String event(int version, String producer, String aggregateId, String payload) {
        return """
                {"eventId":"%s","type":"follow.changed","version":%d,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"%s",
                 "aggregateId":"%s","payload":%s}
                """.formatted(EVENT_ID, version, producer, aggregateId, payload);
    }
}
