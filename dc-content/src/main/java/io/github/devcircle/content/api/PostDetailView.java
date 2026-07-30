package io.github.devcircle.content.api;

import java.time.Instant;
import java.util.List;

public record PostDetailView(
        Long id,
        String type,
        String theme,
        String contentKind,
        String difficulty,
        boolean solvable,
        long solvedCount,
        String title,
        String excerpt,
        String markdown,
        AuthorView author,
        List<String> tags,
        List<PostMediaView> media,
        long likes,
        long comments,
        long views,
        String status,
        Instant createdAt,
        List<String> clues,
        List<String> suspects,
        List<String> timeline,
        String question,
        List<String> worldbuilding,
        List<String> cosmicFacts,
        List<Long> relatedPostIds,
        ViewerState viewerState,
        DeductionState deductionState,
        String solution
) {
    public record AuthorView(Long id, String username, String avatarUrl, String bio,
                             Long followerCount, Boolean followedByViewer) {
    }

    public record ViewerState(boolean liked, boolean favorited, int readingProgress,
                              String readingAnchor, boolean readingCompleted) {
    }

    public record DeductionState(boolean attempted, boolean solved, boolean revealed,
                                 int points, boolean solutionUnlocked) {
    }
}
