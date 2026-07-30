package io.github.devcircle.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

public record PostSummary(
        Long id,
        String type,
        String theme,
        String contentKind,
        String difficulty,
        boolean solvable,
        long solvedCount,
        String title,
        String excerpt,
        String author,
        Long authorId,
        List<String> tags,
        List<MediaAssetView> mediaAssets,
        long likes,
        long comments,
        long views,
        String status,
        Instant createdAt,
        List<String> clues,
        List<String> suspects,
        List<String> timeline,
        String question,
        @JsonIgnore List<String> answerKeywords,
        @JsonIgnore String explanation,
        List<String> worldbuilding,
        List<String> cosmicFacts,
        List<Long> relatedPostIds
) {
    /**
     * Compatibility constructor for one release while callers migrate to the
     * stable author id carried by the canonical record constructor.
     */
    public PostSummary(
            Long id,
            String type,
            String theme,
            String contentKind,
            String difficulty,
            boolean solvable,
            long solvedCount,
            String title,
            String excerpt,
            String author,
            List<String> tags,
            List<MediaAssetView> mediaAssets,
            long likes,
            long comments,
            long views,
            String status,
            Instant createdAt,
            List<String> clues,
            List<String> suspects,
            List<String> timeline,
            String question,
            List<String> answerKeywords,
            String explanation,
            List<String> worldbuilding,
            List<String> cosmicFacts,
            List<Long> relatedPostIds
    ) {
        this(id, type, theme, contentKind, difficulty, solvable, solvedCount, title, excerpt, author, null,
                tags, mediaAssets, likes, comments, views, status, createdAt, clues, suspects, timeline, question,
                answerKeywords, explanation, worldbuilding, cosmicFacts, relatedPostIds);
    }

    public PostSummary(
            Long id,
            String type,
            String theme,
            String contentKind,
            String difficulty,
            boolean solvable,
            long solvedCount,
            String title,
            String excerpt,
            String author,
            Long authorId,
            List<String> tags,
            List<MediaAssetView> mediaAssets,
            long likes,
            long comments,
            long views,
            String status,
            Instant createdAt
    ) {
        this(id, type, theme, contentKind, difficulty, solvable, solvedCount, title, excerpt, author, authorId, tags, mediaAssets, likes, comments, views, status, createdAt,
                List.of(), List.of(), List.of(), "", List.of(), "", List.of(), List.of(), List.of());
    }

    public PostSummary(
            Long id,
            String type,
            String theme,
            String contentKind,
            String difficulty,
            boolean solvable,
            long solvedCount,
            String title,
            String excerpt,
            String author,
            List<String> tags,
            List<MediaAssetView> mediaAssets,
            long likes,
            long comments,
            long views,
            String status,
            Instant createdAt
    ) {
        this(id, type, theme, contentKind, difficulty, solvable, solvedCount, title, excerpt, author, null,
                tags, mediaAssets, likes, comments, views, status, createdAt);
    }

    public PostSummary(
            Long id,
            String type,
            String title,
            String excerpt,
            String author,
            List<String> tags,
            List<MediaAssetView> mediaAssets,
            long likes,
            long comments,
            long views,
            String status,
            Instant createdAt
    ) {
        this(id, type, "DEDUCTION", inferContentKind(type), "MEDIUM", false, 0, title, excerpt, author, null,
                tags, mediaAssets, likes, comments, views, status, createdAt);
    }

    public PostSummary(
            Long id,
            String title,
            String excerpt,
            String author,
            List<String> tags,
            long likes,
            long comments,
            long views,
            String status,
            Instant createdAt
    ) {
        this(id, "TEXT", title, excerpt, author, tags, List.of(), likes, comments, views, status, createdAt);
    }

    private static String inferContentKind(String type) {
        if ("IMAGE".equalsIgnoreCase(type)) {
            return "WORLDBUILDING";
        }
        if ("VIDEO".equalsIgnoreCase(type)) {
            return "OBSERVATION_LOG";
        }
        return "CASE_FILE";
    }
}
