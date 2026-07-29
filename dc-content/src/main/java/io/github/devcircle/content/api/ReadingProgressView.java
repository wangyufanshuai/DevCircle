package io.github.devcircle.content.api;

import java.time.Instant;

public record ReadingProgressView(Long postId, int progressPercent, String anchor,
                                  boolean completed, Instant lastReadAt) {
}
