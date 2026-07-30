package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CommentServiceTest {
    @Test
    void writeRequiresIdempotencyKey() {
        CommentService service = new CommentService(mock(CommentRepository.class));

        assertThatThrownBy(() -> service.create(7L, "", new CreateCommentRequest(1001L, null, "有效评论")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void moderationRequiresAReviewableReason() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertThatThrownBy(() -> service.hide(9L, 7L, "ADMIN", "hide-key", "bad"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写");
        verifyNoInteractions(repository);
    }

    @Test
    void moderationTrimsReasonBeforeWritingAudit() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        service.hide(9L, 7L, "MODERATOR", "hide-key", "  泄露他人隐私  ");

        verify(repository).hide(9L, 7L, "hide-key", "泄露他人隐私");
    }

    @Test
    void roleNamesMustMatchExactly() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertThatThrownBy(() -> service.hide(9L, 7L, "NOT_ADMIN", "hide-key", "泄露他人隐私"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审核权限");
        verifyNoInteractions(repository);
    }
}
