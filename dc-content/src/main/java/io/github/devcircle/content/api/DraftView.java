package io.github.devcircle.content.api;

import java.time.Instant;
import java.util.List;

public record DraftView(
        Long id,
        Long authorId,
        String title,
        String content,
        List<String> tags,
        String type,
        List<Long> mediaIds,
        List<DraftMediaView> media,
        String theme,
        String contentKind,
        String difficulty,
        boolean solvable,
        String clues,
        String suspects,
        String timeline,
        String question,
        String answerKeywords,
        String solution,
        String status,
        long version,
        Instant lastSavedAt,
        Instant submittedAt,
        String reviewReason
) {
    public DraftView withMedia(List<DraftMediaView> items) {
        return new DraftView(id, authorId, title, content, tags, type, mediaIds, List.copyOf(items), theme,
                contentKind, difficulty, solvable, clues, suspects, timeline, question, answerKeywords, solution,
                status, version, lastSavedAt, submittedAt, reviewReason);
    }
}
