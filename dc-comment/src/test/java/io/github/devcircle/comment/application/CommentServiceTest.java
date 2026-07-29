package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CommentServiceTest {
    @Test
    void writeRequiresIdempotencyKey() {
        CommentService service = new CommentService(mock(CommentRepository.class));

        assertThatThrownBy(() -> service.create(7L, "", new CreateCommentRequest(1001L, null, "有效评论")))
                .isInstanceOf(BusinessException.class);
    }
}
