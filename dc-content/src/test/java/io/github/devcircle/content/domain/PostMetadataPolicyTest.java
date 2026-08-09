package io.github.devcircle.content.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostMetadataPolicyTest {

    @Test
    void normalizesSupportedMetadataWithoutLocaleDependentBehavior() {
        assertThat(PostMetadataPolicy.normalizeType(" video ")).isEqualTo("VIDEO");
        assertThat(PostMetadataPolicy.normalizeTheme("sci_fi")).isEqualTo("SCI_FI");
        assertThat(PostMetadataPolicy.normalizeContentKind("observation_log")).isEqualTo("OBSERVATION_LOG");
        assertThat(PostMetadataPolicy.normalizeDifficulty("hard")).isEqualTo("HARD");
    }

    @Test
    void appliesCanonicalFallbacksAndBoundsTags() {
        assertThat(PostMetadataPolicy.normalizeType("audio")).isEqualTo("TEXT");
        assertThat(PostMetadataPolicy.normalizeTheme(null)).isEqualTo("DEDUCTION");
        assertThat(PostMetadataPolicy.normalizeContentKind("unknown")).isEqualTo("CASE_FILE");
        assertThat(PostMetadataPolicy.normalizeDifficulty("extreme")).isEqualTo("MEDIUM");
        assertThat(PostMetadataPolicy.normalizeTags(List.of(" 推理 ", "", "推理", "宇宙")))
                .containsExactly("推理", "宇宙");
    }
}
