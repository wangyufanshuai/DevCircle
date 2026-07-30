package io.github.devcircle.common.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeContentLibraryTest {
    @Test
    void containsExpandedDeductionLibraryAndFortyExamplesForOtherThemes() {
        assertThat(ThemeContentLibrary.posts()).hasSize(180);
        assertThat(ThemeContentLibrary.themeCounts())
                .containsEntry("DEDUCTION", 100L)
                .containsEntry("SCI_FI", 40L)
                .containsEntry("COSMOS", 40L);
    }

    @Test
    void detailsAndRelatedPostsAreMapped() {
        var post = ThemeContentLibrary.find(1001L).orElseThrow();

        assertThat(post.clues()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(post.suspects()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(post.timeline()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(post.answerKeywords()).contains("夹层藏身", "时间差");
        assertThat(ThemeContentLibrary.related(1001L, 6)).isNotEmpty();
    }

    @Test
    void deductionExamplesHaveCompleteCaseFields() {
        var deductionPosts = ThemeContentLibrary.posts().stream()
                .filter(post -> "DEDUCTION".equals(post.theme()))
                .toList();

        assertThat(deductionPosts).hasSize(100);
        assertThat(deductionPosts)
                .allSatisfy(post -> {
                    assertThat(post.title()).isNotBlank();
                    assertThat(post.excerpt()).isNotBlank();
                    assertThat(post.tags()).hasSizeGreaterThanOrEqualTo(4);
                    assertThat(post.clues()).hasSizeGreaterThanOrEqualTo(5);
                    assertThat(post.suspects()).hasSizeGreaterThanOrEqualTo(4);
                    assertThat(post.timeline()).hasSizeGreaterThanOrEqualTo(5);
                    assertThat(post.question()).isNotBlank();
                    assertThat(post.answerKeywords()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(post.explanation()).isNotBlank();
                });
    }

    @Test
    void seedAuthorsUseReservedStableIdentities() {
        assertThat(ThemeContentLibrary.authors()).hasSize(10);
        assertThat(ThemeContentLibrary.authors().keySet())
                .allMatch(id -> id >= 9_000_000_001L && id <= 9_000_000_010L);
        assertThat(ThemeContentLibrary.summaries())
                .allSatisfy(post -> assertThat(post.authorId())
                        .isEqualTo(ThemeContentLibrary.authorId(post.author())));
    }
}
