package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.PostQueryRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PostServiceAppealTest {
    private final PostRepository repository = mock(PostRepository.class);
    private final PostService service = new PostService(
            repository, mock(PostQueryRepository.class), mock(ReadingProgressRepository.class));

    @Test
    void appealRequiresAnActionableReason() {
        assertThatThrownBy(() -> service.createAppeal(2L, 12L, "短", "appeal-12"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("4 至 512");
        verifyNoInteractions(repository);
    }

    @Test
    void validAppealIsDelegatedWithItsIdempotencyKey() {
        service.createAppeal(2L, 12L, "  审核意见遗漏了附件中的来源说明  ", "appeal-12");

        verify(repository).createAppeal(2L, 12L, "审核意见遗漏了附件中的来源说明", "appeal-12");
    }
}
