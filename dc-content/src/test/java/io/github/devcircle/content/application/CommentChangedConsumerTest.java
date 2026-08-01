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
        consumer.consume("""
                {"eventId":"11111111-1111-1111-1111-111111111111","type":"comment.created",
                 "version":1,"payload":{"commentId":9,"postId":1001,"changeType":"CREATED"}}
                """);

        verify(repository).apply("11111111-1111-1111-1111-111111111111",
                EventTopics.COMMENT_CREATED, 1001L, "CREATED");
    }

    @Test
    void projectsCreatedCommentOnTheOrderedLifecycleTopic() throws Exception {
        consumer.consume("""
                {"eventId":"33333333-3333-3333-3333-333333333333","type":"comment.changed",
                 "version":1,"payload":{"commentId":10,"postId":1002,"changeType":"CREATED"}}
                """);

        verify(repository).apply("33333333-3333-3333-3333-333333333333",
                EventTopics.COMMENT_CHANGED, 1002L, "CREATED");
    }

    @Test
    void rejectsCommentEventWithoutPostId() {
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"22222222-2222-2222-2222-222222222222","type":"comment.changed",
                 "payload":{"changeType":"DELETED"}}
                """))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }
}
