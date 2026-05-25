package io.github.devcircle.common.model;

import java.time.Instant;

public record NotificationView(Long id, String type, String title, String content, boolean read, Instant createdAt) {
}

