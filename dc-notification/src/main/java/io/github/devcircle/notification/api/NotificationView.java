package io.github.devcircle.notification.api;

import java.time.Instant;

public record NotificationView(Long id, String type, String title, String content, boolean read,
                               Long actorId, String targetType, Long targetId, String targetRoute,
                               Instant createdAt) {
}
