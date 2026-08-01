package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(dedup.claim(EVENT_ID, "follow.changed")).thenReturn(true);

        consumer.consume(event("7", """
                {"actorId":2,"receiverId":7,"receiverUsername":"林墨","status":"FOLLOWED"}
                """));

        verify(signals).apply(EVENT_ID, 2L, 7L, "FOLLOWED");
        verify(dedup).markCompleted(EVENT_ID);
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
        when(dedup.claim(EVENT_ID, "follow.changed")).thenReturn(true);

        assertThatThrownBy(() -> consumer.consume(event("99", """
                {"actorId":2,"receiverId":7,"status":"FOLLOWED"}
                """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Follow aggregateId does not match receiverId");

        verify(dedup).markFailed(EVENT_ID, "Follow aggregateId does not match receiverId");
        verifyNoInteractions(signals);
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
