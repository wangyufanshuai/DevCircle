package io.github.devcircle.user.api;

public record FollowResult(Long targetUserId, boolean followed, boolean duplicated) {
}
