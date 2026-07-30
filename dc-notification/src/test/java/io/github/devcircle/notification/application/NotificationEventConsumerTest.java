package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventConsumerTest {
    @Test
    void acceptedAppealCreatesAnAuditableTargetedNotification() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper);
        EventEnvelope event = EventEnvelope.now(EventTopics.APPEAL_RESOLVED, "dc-admin", "12",
                Map.of("postId", 12L, "receiverId", 2L, "action", "APPEAL_APPROVED", "status", "PENDING"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(repository).createOnce(event.eventId().toString(), EventTopics.APPEAL_RESOLVED, 2L, null,
                "AUDIT", "申诉处理结果", "你的申诉已受理，投稿已进入人工复审队列", "POST", 12L, "/posts/12");
    }

    @Test
    void interactionWithoutReceiverResolvesThePublishedPostAuthor() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationTargetResolver resolver = mock(NotificationTargetResolver.class);
        when(resolver.postAuthorId(9001L)).thenReturn(7L);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper, resolver);
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "LIKE"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(repository).createOnce(event.eventId().toString(), EventTopics.INTERACTION_CHANGED, 7L, 2L,
                "INTERACTION", "新的内容互动", "你的内容收到了一次新的互动", "POST", 9001L, "/posts/9001");
    }
}
