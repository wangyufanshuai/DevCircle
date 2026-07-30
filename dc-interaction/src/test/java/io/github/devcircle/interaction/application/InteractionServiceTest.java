package io.github.devcircle.interaction.application;

import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
