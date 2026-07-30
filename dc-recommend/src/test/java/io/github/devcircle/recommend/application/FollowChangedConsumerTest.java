package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowChangedConsumerTest {
    @Test
    void appliesStableAuthorIdentityToTheFollowProjectionAfterClaimingTheEvent() throws Exception {
        InteractionConsumerDedup dedup = mock(InteractionConsumerDedup.class);
        FollowSignalStore signals = mock(FollowSignalStore.class);
        FollowChangedConsumer consumer = new FollowChangedConsumer(new ObjectMapper(), dedup, signals);
        when(dedup.claim("evt-follow", "follow.changed")).thenReturn(true);

        consumer.consume("""
                {"eventId":"evt-follow","type":"follow.changed","payload":{
                  "actorId":2,"receiverId":7,"receiverUsername":"林墨","status":"FOLLOWED"}}
                """);

        verify(signals).apply("evt-follow", 2L, 7L, "FOLLOWED");
        verify(dedup).markCompleted("evt-follow");
    }
}
