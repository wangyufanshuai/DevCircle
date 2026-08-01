package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.infrastructure.ContentCommentProjectionRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        verify(repository).apply("11111111-1111-1111-1111-111111111111",
                EventTopics.COMMENT_CREATED, 1001L, "CREATED");
    }

    @Test
    void projectsCreatedCommentOnTheOrderedLifecycleTopic() throws Exception {
        consumer.consume(event("33333333-3333-3333-3333-333333333333",
                EventTopics.COMMENT_CHANGED, "10", """
                        {"commentId":10,"postId":1002,"changeType":"CREATED"}
                        """));

        verify(repository).apply("33333333-3333-3333-3333-333333333333",
                EventTopics.COMMENT_CHANGED, 1002L, "CREATED");
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

    private String event(String eventId, String type, String aggregateId, String payload) {
        return """
                {"eventId":"%s","type":"%s","version":1,
                 "occurredAt":"2026-08-01T10:15:30Z","producer":"dc-comment",
                 "aggregateId":"%s","payload":%s}
                """.formatted(eventId, type, aggregateId, payload);
    }
}
