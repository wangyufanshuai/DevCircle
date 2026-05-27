package io.github.devcircle.common.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeContentLibraryTest {
    @Test
    void containsFortyExamplesForEachTheme() {
        assertThat(ThemeContentLibrary.posts()).hasSize(120);
        assertThat(ThemeContentLibrary.themeCounts())
                .containsEntry("DEDUCTION", 40L)
                .containsEntry("SCI_FI", 40L)
                .containsEntry("COSMOS", 40L);
    }

    @Test
    void detailsAndRelatedPostsAreMapped() {
        var post = ThemeContentLibrary.find(1001L).orElseThrow();

        assertThat(post.clues()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(post.suspects()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(post.timeline()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(post.answerKeywords()).contains("夹层");
        assertThat(ThemeContentLibrary.related(1001L, 6)).isNotEmpty();
    }
}
