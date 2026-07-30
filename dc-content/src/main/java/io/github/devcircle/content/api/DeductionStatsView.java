package io.github.devcircle.content.api;

public record DeductionStatsView(long attempts, long correctCount, long revealedCount, long totalPoints) {
}
