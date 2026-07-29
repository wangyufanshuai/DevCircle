package io.github.devcircle.content.api;

import java.time.Instant;

public record PostAuditView(Long id, Long postId, Long auditorId, String action, String reason, Instant createdAt) {
}
