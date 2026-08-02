package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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

    @Test
    void followAggregateMustMatchReceiverAndStatusMustBeSupported() throws Exception {
        EventEnvelope wrongAggregate = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "8",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "FOLLOWED"));
        EventEnvelope wrongStatus = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "receiverId", 7L, "status", "PENDING"));

        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(wrongAggregate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Follow aggregateId does not match receiverId");
        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(wrongStatus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported follow status: PENDING");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void unresolvedInteractionReceiverIsRejected() throws Exception {
        NotificationEventConsumer consumerWithoutResolver = new NotificationEventConsumer(repository, objectMapper);
        EventEnvelope event = EventEnvelope.now(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "LIKE"));

        assertThatThrownBy(() -> consumerWithoutResolver.consume(objectMapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification event is missing receiverId");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void interactionActorAndTransitionMustBeSupported() {
        assertInvalid(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("postId", 9001L, "interactionType", "LIKE"),
                "Interaction notification event is missing actorId");
        assertInvalid(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L),
                "Unsupported interaction type: ");
        assertInvalid(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("actorId", 2L, "postId", 9001L, "interactionType", "SHARE"),
                "Unsupported interaction type: SHARE");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void followTransitionRequiresActorIdentity() {
        for (String status : new String[]{"FOLLOWED", "UNFOLLOWED"}) {
            EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                    Map.of("receiverId", 7L, "status", status));

            assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(event)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Follow notification event is missing actorId");
        }

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void auditStatusMustBePresentTextualAndNonBlank() {
        assertInvalid(EventTopics.AUDIT_COMPLETED, "dc-admin", "12",
                Map.of("receiverId", 2L, "postId", 12L),
                "Audit notification event is missing status");
        assertInvalid(EventTopics.AUDIT_COMPLETED, "dc-admin", "12",
                Map.of("receiverId", 2L, "postId", 12L, "status", " "),
                "Audit notification event is missing status");
        assertInvalid(EventTopics.AUDIT_COMPLETED, "dc-admin", "12",
                Map.of("receiverId", 2L, "postId", 12L, "status", 7),
                "Notification payload status must be a string");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void malformedOptionalIdentityAndNavigationFieldsAreRejected() {
        assertInvalid(EventTopics.INTERACTION_CHANGED, "dc-interaction", "9001",
                Map.of("receiverId", "7", "postId", 9001L, "interactionType", "LIKE"),
                "Notification payload receiverId must be a positive integer");
        assertInvalid(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("receiverId", 7L, "actorId", "2", "status", "FOLLOWED"),
                "Notification payload actorId must be a positive integer");
        assertInvalid(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("receiverId", 7L, "actorId", 2L, "targetId", 2L,
                        "targetType", 7, "status", "FOLLOWED"),
                "Notification payload targetType must be a string");

        verifyNoInteractions(repository, resolver);
    }

    @Test
    void followWithoutNavigationTargetCreatesANonNavigableNotification() throws Exception {
        EventEnvelope event = EventEnvelope.now(EventTopics.FOLLOW_CHANGED, "dc-user", "7",
                Map.of("actorId", 2L, "actorUsername", "观测者", "receiverId", 7L, "status", "FOLLOWED"));

        consumer.consume(objectMapper.writeValueAsString(event));

        verify(repository).createOnce(matching(event), eq(7L), eq(2L),
                eq("FOLLOW"), eq("新的关注者"), eq("观测者 关注了你"), eq(""), isNull(), eq(""));
        verifyNoInteractions(resolver);
    }

    @Test
    void completedAuditAndResolvedAppealUseTheirDistinctMessages() throws Exception {
        EventEnvelope audit = EventEnvelope.now(EventTopics.AUDIT_COMPLETED, "dc-admin", "12",
                Map.of("receiverId", 2L, "postId", 12L, "status", "PUBLISHED"));
        EventEnvelope appeal = EventEnvelope.now(EventTopics.APPEAL_RESOLVED, "dc-admin", "13",
                Map.of("receiverId", 2L, "postId", 13L, "status", "PUBLISHED"));

        consumer.consume(objectMapper.writeValueAsString(audit));
        consumer.consume(objectMapper.writeValueAsString(appeal));

        verify(repository).createOnce(matching(audit), eq(2L), isNull(),
                eq("AUDIT"), eq("投稿审核状态更新"), eq("你的投稿状态已更新为 PUBLISHED"),
                eq("POST"), eq(12L), eq("/posts/12"));
        verify(repository).createOnce(matching(appeal), eq(2L), isNull(),
                eq("AUDIT"), eq("申诉处理结果"), eq("你的申诉已处理，请查看审核时间线"),
                eq("POST"), eq(13L), eq("/posts/13"));
        verifyNoInteractions(resolver);
    }

    private void assertInvalid(String type, String producer, String aggregateId,
                               Map<String, Object> payload, String message) {
        EventEnvelope event = EventEnvelope.now(type, producer, aggregateId, payload);
        assertThatThrownBy(() -> consumer.consume(objectMapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private ValidatedEvent validated(EventEnvelope event) {
        return new ValidatedEvent(event.eventId(), event.type(), event.version(), event.occurredAt(),
                event.producer(), event.aggregateId(), objectMapper.valueToTree(event.payload()));
    }

    private ValidatedEvent matching(EventEnvelope event) {
        String expectedFingerprint = EventFingerprint.sha256(validated(event));
        return argThat(actual -> actual != null
                && actual.eventId().equals(event.eventId())
                && EventFingerprint.sha256(actual).equals(expectedFingerprint));
    }
}
