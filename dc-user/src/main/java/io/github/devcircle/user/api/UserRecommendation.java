package io.github.devcircle.user.api;

public record UserRecommendation(Long userId, long mutualFollowees, String reason) {
}
