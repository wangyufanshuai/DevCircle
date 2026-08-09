package io.github.devcircle.recommend.application;

import io.github.devcircle.content.contract.PostSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationScenePolicyTest {
    private final RecommendationScenePolicy policy = new RecommendationScenePolicy();

    @Test
    void normalizesOnlyTheSupportedPublicVocabulary() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(policy.normalize("SCI_FI")).isEqualTo("sci_fi");
        } finally {
            Locale.setDefault(original);
        }
        assertThat(policy.normalize("unknown")).isEqualTo("mixed");
        assertThat(policy.normalize(" ")).isEqualTo("mixed");
    }

    @Test
    void mapsOnlyThemeScenesToContentQueryFilters() {
        assertThat(policy.queryTheme("deduction")).isEqualTo("DEDUCTION");
        assertThat(policy.queryTheme("SCI_FI")).isEqualTo("SCI_FI");
        assertThat(policy.queryTheme("cosmos")).isEqualTo("COSMOS");
        assertThat(policy.queryTheme("home")).isNull();
        assertThat(policy.queryTheme("video")).isNull();
    }

    @Test
    void matchesGlobalMediaAndThemeScenesWithoutConflatingThem() {
        PostSummary scienceFictionImage = post("IMAGE", "SCI_FI");

        assertThat(policy.matches(scienceFictionImage, "home")).isTrue();
        assertThat(policy.matches(scienceFictionImage, "image")).isTrue();
        assertThat(policy.matches(scienceFictionImage, "video")).isFalse();
        assertThat(policy.matches(scienceFictionImage, "sci_fi")).isTrue();
        assertThat(policy.matches(scienceFictionImage, "deduction")).isFalse();
    }

    private PostSummary post(String type, String theme) {
        return new PostSummary(1L, type, theme, "CASE_FILE", "MEDIUM", false, 0,
                "测试内容", "摘要", "author", 7L, List.of(), List.of(),
                0, 0, 0, "PUBLISHED", Instant.now());
    }
}
