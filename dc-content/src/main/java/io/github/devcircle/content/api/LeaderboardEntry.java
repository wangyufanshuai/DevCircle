package io.github.devcircle.content.api;

public record LeaderboardEntry(Long userId, String username, long correctCount, long totalPoints) {
}
