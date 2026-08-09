package io.github.devcircle.content.domain;

import java.util.List;
import java.util.Locale;

/** Canonical normalization rules shared by content commands and draft persistence. */
public final class PostMetadataPolicy {
    private static final List<String> TYPES = List.of("TEXT", "IMAGE", "VIDEO");
    private static final List<String> THEMES = List.of("DEDUCTION", "SCI_FI", "COSMOS");
    private static final List<String> CONTENT_KINDS = List.of(
            "CASE_FILE", "PUZZLE", "FICTION", "WORLDBUILDING", "COSMIC_ARTICLE", "OBSERVATION_LOG");
    private static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");

    private PostMetadataPolicy() {
    }

    public static String normalizeType(String value) {
        return normalize(value, "TEXT", TYPES);
    }

    public static String normalizeTheme(String value) {
        return normalize(value, "DEDUCTION", THEMES);
    }

    public static String normalizeContentKind(String value) {
        return normalize(value, "CASE_FILE", CONTENT_KINDS);
    }

    public static String normalizeDifficulty(String value) {
        return normalize(value, "MEDIUM", DIFFICULTIES);
    }

    public static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(tag -> tag != null)
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .limit(10)
                .toList();
    }

    private static String normalize(String value, String fallback, List<String> allowed) {
        String normalized = value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }
}
