package io.github.devcircle.content.application;

import io.github.devcircle.content.infrastructure.PostQueryRepository;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostServiceSummaryTest {
    @Test
    void delegatesFavoriteSummaryBatchToOneRepositoryOperation() {
        PostQueryRepository repository = mock(PostQueryRepository.class);
        PostService service = new PostService(
                mock(PostRepository.class), repository, mock(ReadingProgressRepository.class));
        List<Long> ids = List.of(1001L, 1002L, 1001L);

        service.publicSummaries(ids);

        verify(repository).publicSummaries(ids);
    }
}
