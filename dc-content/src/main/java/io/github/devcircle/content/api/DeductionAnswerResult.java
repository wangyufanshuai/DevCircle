package io.github.devcircle.content.api;

public record DeductionAnswerResult(Long postId, Long userId, boolean correct, boolean duplicated, int points, String explanation) {
}
