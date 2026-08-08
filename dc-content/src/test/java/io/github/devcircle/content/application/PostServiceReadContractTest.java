package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceReadContractTest {
    private PostRepository repository;
    private PostService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostRepository.class);
        service = new PostService(repository, mock(ReadingProgressRepository.class));
    }

    @Test
    void databaseListClampsPageSizeAndAppliesLegacyTypeFilter() {
        PostSummary text = mock(PostSummary.class);
        PostSummary image = mock(PostSummary.class);
        when(text.type()).thenReturn("TEXT");
        when(image.type()).thenReturn("IMAGE");
        when(repository.list(3, 20, "DEDUCTION", null, null)).thenReturn(List.of(text, image));

        var result = service.list(3, 500, "image", "DEDUCTION", null, null);

        assertThat(result.records()).containsExactly(image);
        assertThat(result.size()).isEqualTo(20);
        verify(repository).list(3, 20, "DEDUCTION", null, null);
    }

    @Test
    void developmentFallbackFiltersThemeKindAndDifficultyWithoutChangingPageBounds() {
        ReflectionTestUtils.setField(service, "readFallbackEnabled", true);
        PostSummary seed = ThemeContentLibrary.summaries().getFirst();
        when(repository.list(1, 1, seed.theme(), seed.contentKind(), seed.difficulty()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        var result = service.list(1, 0, null, seed.theme(), seed.contentKind(), seed.difficulty());

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.records()).isNotEmpty()
                .allSatisfy(post -> {
                    assertThat(post.theme()).isEqualToIgnoringCase(seed.theme());
                    assertThat(post.contentKind()).isEqualToIgnoringCase(seed.contentKind());
                    assertThat(post.difficulty()).isEqualToIgnoringCase(seed.difficulty());
                });
    }

    @Test
    void publicDetailRejectsUnpublishedDatabaseRows() {
        PostSummary draft = mock(PostSummary.class);
        when(draft.status()).thenReturn("DRAFT");
        when(repository.detail(11L)).thenReturn(draft);

        assertThatThrownBy(() -> service.detail(11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void developmentDetailFallbackReturnsKnownSeedButNotUnknownContent() {
        ReflectionTestUtils.setField(service, "readFallbackEnabled", true);
        long seedId = ThemeContentLibrary.summaries().getFirst().id();
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("database unavailable");
        when(repository.detail(seedId)).thenThrow(outage);
        when(repository.detail(Long.MAX_VALUE)).thenThrow(outage);

        assertThat(service.detail(seedId).id()).isEqualTo(seedId);
        assertThatThrownBy(() -> service.detail(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void developmentRelatedFallbackRemainsBoundedToTheRequestedSize() {
        ReflectionTestUtils.setField(service, "readFallbackEnabled", true);
        long seedId = ThemeContentLibrary.summaries().getFirst().id();
        when(repository.related(seedId, 3))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(service.related(seedId, 3)).hasSizeLessThanOrEqualTo(3);
    }
}
