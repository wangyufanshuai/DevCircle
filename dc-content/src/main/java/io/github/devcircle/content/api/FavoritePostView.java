package io.github.devcircle.content.api;

import java.time.Instant;

public record FavoritePostView(Long id, String title, String excerpt, String theme, String contentKind,
                               String difficulty, Instant createdAt) {
}
