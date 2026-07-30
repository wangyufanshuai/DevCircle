package io.github.devcircle.common.seed;

import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;

import java.time.Instant;
import java.util.List;

public record ThemeSeedPost(
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
        Instant createdAt,
        String reason,
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
    public PostSummary toSummary() {
        return new PostSummary(id, type, theme, contentKind, difficulty, solvable, solvedCount, title, excerpt, author,
                ThemeContentLibrary.authorId(author), tags, mediaAssets,
                likes, comments, views, "PUBLISHED", createdAt, clues, suspects, timeline, question, answerKeywords, explanation,
                worldbuilding, cosmicFacts, relatedPostIds);
    }
}
