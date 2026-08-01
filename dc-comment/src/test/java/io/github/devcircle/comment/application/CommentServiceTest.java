package io.github.devcircle.comment.application;

import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class CommentServiceTest {
    @Test
    void writeRequiresIdempotencyKey() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertThatThrownBy(() -> service.create(7L, "", new CreateCommentRequest(1001L, null, "有效评论")))
                .isInstanceOf(BusinessException.class);
        assertCode(() -> service.create(7L, null, new CreateCommentRequest(1001L, null, "有效评论")),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "x".repeat(129),
                new CreateCommentRequest(1001L, null, "有效评论")), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(repository);
    }

    @Test
    void moderationRequiresAReviewableReason() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertThatThrownBy(() -> service.hide(9L, 7L, "ADMIN", "hide-key", "bad"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写");
        assertCode(() -> service.hide(9L, 7L, "ADMIN", "hide-key", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.hide(9L, 7L, "ADMIN", "hide-key", "   "), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.hide(9L, 7L, "ADMIN", "hide-key", "x".repeat(501)),
                ErrorCode.VALIDATION_FAILED);
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

    @Test
    void commentCreationRequiresPublishedPost() {
        CommentRepository repository = mock(CommentRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        doThrow(new BusinessException(io.github.devcircle.common.error.ErrorCode.RESOURCE_NOT_FOUND,
                "内容不存在或尚未发布")).when(publicationGateway).requirePublished(1001L);
        CommentService service = new CommentService(repository, publicationGateway);

        assertThatThrownBy(() -> service.create(7L, "comment-key",
                new CreateCommentRequest(1001L, null, "clue")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void serviceBoundaryRejectsInvalidCreateRequestsBeforePublicationLookup() {
        CommentRepository repository = mock(CommentRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        CommentService service = new CommentService(repository, publicationGateway);

        assertCode(() -> service.create(7L, "comment-key", null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "comment-key", new CreateCommentRequest(0L, null, "comment")),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "comment-key", new CreateCommentRequest(1001L, 0L, "comment")),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "comment-key", new CreateCommentRequest(1001L, null, "   ")),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "comment-key", new CreateCommentRequest(1001L, null, null)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(7L, "comment-key",
                new CreateCommentRequest(1001L, null, "x".repeat(2001))), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository, publicationGateway);
    }

    @Test
    void deleteAndHideRequireAuthenticatedActorsAndValidCommentIds() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertCode(() -> service.deleteOwn(9L, null, "delete-key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.deleteOwn(9L, 0L, "delete-key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.deleteOwn(null, 7L, "delete-key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.deleteOwn(0L, 7L, "delete-key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.hide(9L, null, "ADMIN", "hide-key", "泄露个人隐私"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.hide(0L, 7L, "ADMIN", "hide-key", "泄露个人隐私"),
                ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    void listEndpointsRejectInvalidPostIdsBeforeRepositoryQueries() {
        CommentRepository repository = mock(CommentRepository.class);
        CommentService service = new CommentService(repository);

        assertCode(() -> service.list(null, null, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.listThreads(0L, null, 20, null), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository);
    }

    @Test
    void validOperationsDelegateNormalizedBoundsAndModeratorState() {
        CommentRepository repository = mock(CommentRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        CommentService service = new CommentService(repository, publicationGateway);
        CreateCommentRequest request = new CreateCommentRequest(1001L, null, "  有效评论  ");
        when(repository.list(1001L, null, 1)).thenReturn(java.util.List.of());
        when(repository.listThreads(1001L, null, 51, true)).thenReturn(java.util.List.of());
        when(repository.listThreads(1001L, null, 20, false)).thenReturn(java.util.List.of());

        service.list(1001L, null, 0);
        service.listThreads(1001L, null, 100, " USER, ADMIN ");
        service.listThreads(1001L, null, 20, null);
        service.create(7L, "create-key", request);
        service.deleteOwn(9L, 7L, "delete-key");

        verify(repository).list(1001L, null, 1);
        verify(repository).listThreads(1001L, null, 51, true);
        verify(repository).listThreads(1001L, null, 20, false);
        verify(publicationGateway).requirePublished(1001L);
        verify(repository).create(7L, 1001L, null, "  有效评论  ", "create-key");
        verify(repository).deleteOwn(9L, 7L, "delete-key");
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
