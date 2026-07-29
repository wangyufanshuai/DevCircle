package io.github.devcircle.content.api;

public record DeductionAnswerResult(Long postId, Long userId, boolean correct, boolean duplicated,
                                    boolean revealed, boolean solutionUnlocked, int points,
                                    String explanation, String solution) {
}
