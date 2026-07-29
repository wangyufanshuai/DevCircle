package io.github.devcircle.content.api;

import java.time.Instant;

public record SubmissionView(Long postId, String title, String status, Instant updatedAt, String reviewReason) {
}
