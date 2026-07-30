package io.github.devcircle.content.api;

import java.time.Instant;

public record ReadingProgressSummary(Long postId, String title, String theme, int progressPercent,
                                     boolean completed, Instant lastReadAt, boolean available,
                                     String trackState) {
}
