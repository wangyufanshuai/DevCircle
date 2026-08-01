package io.github.devcircle.interaction.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InteractionServiceTest {
    @Test
    void repeatedIdempotencyKeyDoesNotCreateSecondOperation() {
        InteractionRepository repository = mock(InteractionRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        when(publicationGateway.requirePublished(1001L))
                .thenReturn(new PostPublicationGateway.PublishedPost(1001L, 17));
        when(repository.insertLike(1L, 1001L, 17L, "like-1001-user-1"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(true, false))
                .thenReturn(new InteractionRepository.InteractionWriteResult(false, true));
        InteractionService service = new InteractionService(repository, publicationGateway);

        var first = service.like(1L, 1001L, "like-1001-user-1");
        var second = service.like(1L, 1001L, "like-1001-user-1");

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.active()).isTrue();
        verify(publicationGateway, org.mockito.Mockito.times(2)).requirePublished(1001L);
    }

    @Test
    void rejectedPublicationCheckPreventsInteractionWrite() {
        InteractionRepository repository = mock(InteractionRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        org.mockito.Mockito.doThrow(new io.github.devcircle.common.error.BusinessException(
                        io.github.devcircle.common.error.ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布"))
                .when(publicationGateway).requirePublished(1001L);
        InteractionService service = new InteractionService(repository, publicationGateway);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.like(1L, 1001L, "like-1001-user-1"))
                .isInstanceOf(io.github.devcircle.common.error.BusinessException.class);

        verify(repository, never()).insertLike(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void identityResourceAndIdempotencyValidationPrecedeAllDependencies() {
        InteractionRepository repository = mock(InteractionRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        InteractionService service = new InteractionService(repository, publicationGateway);

        assertCode(() -> service.like(null, 1001L, null), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.like(0L, 1001L, "key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.like(1L, null, "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.like(1L, 0L, "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.like(1L, 1001L, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.like(1L, 1001L, " "), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.like(1L, 1001L, "x".repeat(129)), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(repository, publicationGateway);
    }

    @Test
    void allInteractionCommandsDelegateThePublishedBaselineAndReportNoOpAsDuplicate() {
        InteractionRepository repository = mock(InteractionRepository.class);
        PostPublicationGateway publicationGateway = mock(PostPublicationGateway.class);
        when(publicationGateway.requirePublished(1001L))
                .thenReturn(new PostPublicationGateway.PublishedPost(1001L, 17));
        when(repository.insertLike(1L, 1001L, 17L, "like-key"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(true, false));
        when(repository.insertFavorite(1L, 1001L, 17L, "favorite-key"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(false, false));
        when(repository.deleteLike(1L, 1001L, 17L, "unlike-key"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(true, false));
        when(repository.deleteFavorite(1L, 1001L, 17L, "unfavorite-key"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(false, true));
        InteractionService service = new InteractionService(repository, publicationGateway);

        var like = service.like(1L, 1001L, "like-key");
        var favorite = service.favorite(1L, 1001L, "favorite-key");
        var unlike = service.unlike(1L, 1001L, "unlike-key");
        var unfavorite = service.unfavorite(1L, 1001L, "unfavorite-key");

        assertThat(like.active()).isTrue();
        assertThat(like.duplicated()).isFalse();
        assertThat(favorite.active()).isTrue();
        assertThat(favorite.duplicated()).isTrue();
        assertThat(unlike.active()).isFalse();
        assertThat(unlike.duplicated()).isFalse();
        assertThat(unfavorite.active()).isFalse();
        assertThat(unfavorite.duplicated()).isTrue();
        verify(repository).insertFavorite(1L, 1001L, 17L, "favorite-key");
        verify(repository).deleteLike(1L, 1001L, 17L, "unlike-key");
        verify(repository).deleteFavorite(1L, 1001L, 17L, "unfavorite-key");
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
