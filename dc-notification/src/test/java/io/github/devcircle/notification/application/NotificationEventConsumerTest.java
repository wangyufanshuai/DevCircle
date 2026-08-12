package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class NotificationEventConsumerTest {
    @Test
    void livePushFailureDoesNotFailConsumptionAfterDurablePersistence() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "FOLLOWED"));
        when(repository.createOnce(argThat(candidate -> candidate != null), eq(7L), eq(2L),
                eq("FOLLOW"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                isNull(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(messaging)
                .convertAndSendToUser("7", "/queue/notifications", Map.of("type", "REFRESH"));
        NotificationEventConsumer consumer =
                new NotificationEventConsumer(repository, objectMapper, null, messaging);

        assertThatCode(() -> consumer.consume(objectMapper.writeValueAsString(event)))
                .doesNotThrowAnyException();

        verify(repository).createOnce(matching(event, objectMapper), eq(7L), eq(2L),
                eq("FOLLOW"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                isNull(), org.mockito.ArgumentMatchers.anyString());
        verify(messaging).convertAndSendToUser("7", "/queue/notifications", Map.of("type", "REFRESH"));
        verifyNoMoreInteractions(repository, messaging);
    }

    @Test
    void persistedNotificationPushesOneRefreshSignalWhileDuplicateDeliveryStaysSilent() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "FOLLOWED"));
        when(repository.createOnce(argThat(candidate -> candidate != null), eq(7L), eq(2L),
                eq("FOLLOW"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                isNull(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, false);
        NotificationEventConsumer consumer =
                new NotificationEventConsumer(repository, objectMapper, null, messaging);

        String payload = objectMapper.writeValueAsString(event);
        consumer.consume(payload);
        consumer.consume(payload);

        verify(messaging).convertAndSendToUser("7", "/queue/notifications", Map.of("type", "REFRESH"));
        verifyNoMoreInteractions(messaging);
    }

    @Test
    void negativeAndSelfEventsNeverPushRefreshSignals() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationTargetResolver resolver = mock(NotificationTargetResolver.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(resolver.postAuthorId(9001L)).thenReturn(7L);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer =
                new NotificationEventConsumer(repository, objectMapper, resolver, messaging);
        EventEnvelope unlike = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "UNLIKE"));
        EventEnvelope selfLike = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 7L, "postId", 9001L, "interactionType", "LIKE"));
        EventEnvelope unfollow = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "UNFOLLOWED"));

        consumer.consume(objectMapper.writeValueAsString(unlike));
        consumer.consume(objectMapper.writeValueAsString(selfLike));
        consumer.consume(objectMapper.writeValueAsString(unfollow));

        verifyNoInteractions(messaging);
    }

    @Test
    void acceptedAppealCreatesAnAuditableTargetedNotification() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper);
        EventEnvelope event = EventEnvelope.now(EventTopics.APPEAL_RESOLVED, "dc-admin", "12",
                Map.of("postId", 12L, "receiverId", 2L, "action", "APPEAL_APPROVED", "status", "PENDING"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(repository).createOnce(matching(event, objectMapper), eq(2L), isNull(),
                eq("AUDIT"), eq("申诉处理结果"), eq("你的申诉已受理，投稿已进入人工复审队列"),
                eq("POST"), eq(12L), eq("/posts/12"));
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

        verify(repository).createOnce(matching(event, objectMapper), eq(7L), eq(2L),
                eq("INTERACTION"), eq("新的内容互动"), eq("你的内容收到了一次新的互动"),
                eq("POST"), eq(9001L), eq("/posts/9001"));
    }

    @Test
    void interactionWithReceiverDoesNotDependOnTheContentService() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationTargetResolver resolver = mock(NotificationTargetResolver.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper, resolver);
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "receiverId", 7L, "postId", 9001L, "interactionType", "LIKE"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(repository).createOnce(matching(event, objectMapper), eq(7L), eq(2L),
                eq("INTERACTION"), eq("新的内容互动"), eq("你的内容收到了一次新的互动"),
                eq("POST"), eq(9001L), eq("/posts/9001"));
        verifyNoInteractions(resolver);
    }

    @Test
    void newFollowCreatesOneTargetedNotificationWhileUnfollowStaysSilent() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper);
        EventEnvelope followed = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "actorUsername", "观测者", "receiverId", 7L,
                        "targetType", "USER", "targetId", 2L, "targetRoute", "/me", "status", "FOLLOWED"));
        EventEnvelope unfollowed = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "actorUsername", "观测者", "receiverId", 7L,
                        "targetType", "USER", "targetId", 2L, "targetRoute", "/me", "status", "UNFOLLOWED"));

        consumer.consume(objectMapper.writeValueAsString(followed));
        consumer.consume(objectMapper.writeValueAsString(unfollowed));

        verify(repository).createOnce(matching(followed, objectMapper), eq(7L), eq(2L),
                eq("FOLLOW"), eq("新的关注者"), eq("观测者 关注了你"), eq("USER"), eq(2L), eq("/me"));
        verify(repository).acknowledgeOnce(matching(unfollowed, objectMapper));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void negativeAndSelfInteractionsAreAcknowledgedWithoutNotifying() throws Exception {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationTargetResolver resolver = mock(NotificationTargetResolver.class);
        when(resolver.postAuthorId(9001L)).thenReturn(7L);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationEventConsumer consumer = new NotificationEventConsumer(repository, objectMapper, resolver);
        EventEnvelope unlike = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "UNLIKE"));
        EventEnvelope selfLike = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 7L, "postId", 9001L, "interactionType", "LIKE"));

        consumer.consume(objectMapper.writeValueAsString(unlike));
        consumer.consume(objectMapper.writeValueAsString(selfLike));

        verify(repository).acknowledgeOnce(matching(unlike, objectMapper));
        verify(repository).acknowledgeOnce(matching(selfLike, objectMapper));
        verify(resolver).postAuthorId(9001L);
        verifyNoMoreInteractions(repository, resolver);
    }

    private ValidatedEvent validated(EventEnvelope event, ObjectMapper objectMapper) {
        return new ValidatedEvent(event.eventId(), event.type(), event.version(), event.occurredAt(),
                event.producer(), event.aggregateId(), objectMapper.valueToTree(event.payload()));
    }

    private ValidatedEvent matching(EventEnvelope event, ObjectMapper objectMapper) {
        String expectedFingerprint = EventFingerprint.sha256(validated(event, objectMapper));
        return argThat(actual -> actual != null
                && actual.eventId().equals(event.eventId())
                && EventFingerprint.sha256(actual).equals(expectedFingerprint));
    }
}
