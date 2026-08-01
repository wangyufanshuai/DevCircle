package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationEventEnvelopeValidationTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationTargetResolver resolver = mock(NotificationTargetResolver.class);
    private final NotificationEventConsumer consumer =
            new NotificationEventConsumer(repository, objectMapper, resolver);

    @Test
    void producerMustMatchTheSpecificEventType() throws Exception {
        EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-admin", "7",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "FOLLOWED"));

        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event producer dc-admin for type follow.changed");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void aggregateIdentityMustMatchTheNotificationTarget() throws Exception {
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9002",
                Map.of("actorId", 2L, "receiverId", 7L, "postId", 9001L, "interactionType", "LIKE"));

        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification aggregateId does not match targetId");

        verifyNoInteractions(repository, resolver);
    }
}
