package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.content.infrastructure.ContentCommentProjectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CommentChangedConsumerTest {
    private final ContentCommentProjectionRepository repository = mock(ContentCommentProjectionRepository.class);
    private final CommentChangedConsumer consumer = new CommentChangedConsumer(repository, new ObjectMapper());

    @Test
    void projectsLegacyCreatedComment() throws Exception {
        consumer.consume(event("11111111-1111-1111-1111-111111111111",
                EventTopics.COMMENT_CREATED, "9", """
                        {"commentId":9,"postId":1001,"changeType":"CREATED"}
                        """));

        ArgumentCaptor<ValidatedEvent> eventCaptor = ArgumentCaptor.forClass(ValidatedEvent.class);
        verify(repository).apply(eventCaptor.capture(), eq(1001L), eq("CREATED"));
        assertThat(eventCaptor.getValue().eventId().toString())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(eventCaptor.getValue().type()).isEqualTo(EventTopics.COMMENT_CREATED);
    }

    @Test
    void projectsCreatedCommentOnTheOrderedLifecycleTopic() throws Exception {
        consumer.consume(event("33333333-3333-3333-3333-333333333333",
                EventTopics.COMMENT_CHANGED, "10", """
                        {"commentId":10,"postId":1002,"changeType":"CREATED"}
                        """));

        ArgumentCaptor<ValidatedEvent> eventCaptor = ArgumentCaptor.forClass(ValidatedEvent.class);
        verify(repository).apply(eventCaptor.capture(), eq(1002L), eq("CREATED"));
        assertThat(eventCaptor.getValue().eventId().toString())
                .isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(eventCaptor.getValue().type()).isEqualTo(EventTopics.COMMENT_CHANGED);
    }

    @Test
    void rejectsCommentEventWithoutPostId() {
        assertThatThrownBy(() -> consumer.consume(event("22222222-2222-2222-2222-222222222222",
                EventTopics.COMMENT_CHANGED, "11", """
                        {"commentId":11,"changeType":"DELETED"}
                        """))).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsLegacyLifecycleAndAggregateIdentityMismatch() {
        assertThatThrownBy(() -> consumer.consume(event("44444444-4444-4444-4444-444444444444",
                EventTopics.COMMENT_CREATED, "12", """
                        {"commentId":12,"postId":1001,"changeType":"DELETED"}
                        """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Legacy comment.created only supports CREATED changes");
        assertThatThrownBy(() -> consumer.consume(event("55555555-5555-4555-8555-555555555555",
                EventTopics.COMMENT_CHANGED, "99", """
                        {"commentId":13,"postId":1001,"changeType":"HIDDEN"}
                        """))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Comment aggregateId does not match commentId");

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedCommentPayloadFieldsBeforeProjection() {
        assertRejected("{\"commentId\":\"13\",\"postId\":1001,\"changeType\":\"CREATED\"}",
                "Comment payload commentId must be a positive integer");
        assertRejected("{\"commentId\":0,\"postId\":1001,\"changeType\":\"CREATED\"}",
                "Comment payload commentId must be a positive integer");
        assertRejected("{\"commentId\":999999999999999999999999999999,\"postId\":1001,\"changeType\":\"CREATED\"}",
                "Comment payload commentId must be a positive integer");
        assertRejected("{\"commentId\":13,\"postId\":\"1001\",\"changeType\":\"CREATED\"}",
                "Comment payload postId must be a positive integer");
        assertRejected("{\"commentId\":13,\"postId\":1001}",
                "Comment payload changeType must be a non-blank string");
        assertRejected("{\"commentId\":13,\"postId\":1001,\"changeType\":7}",
                "Comment payload changeType must be a non-blank string");
        assertRejected("{\"commentId\":13,\"postId\":1001,\"changeType\":\" \"}",
                "Comment payload changeType must be a non-blank string");
        assertRejected("{\"commentId\":13,\"postId\":1001,\"changeType\":\"UPDATED\"}",
                "Unsupported comment change type: UPDATED");

        verifyNoInteractions(repository);
    }

    private void assertRejected(String payload, String message) {
        assertThatThrownBy(() -> consumer.consume(event(
                "55555555-5555-4555-8555-555555555555", EventTopics.COMMENT_CHANGED, "13", payload)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private String event(String eventId, String type, String aggregateId, String payload) {
        return """
                {"eventId":"%s","type":"%s","version":1,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"dc-comment",
                 "aggregateId":"%s","payload":%s}
                """.formatted(eventId, type, aggregateId, payload);
    }
}
