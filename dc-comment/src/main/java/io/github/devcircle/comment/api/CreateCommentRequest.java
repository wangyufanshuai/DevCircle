package io.github.devcircle.comment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateCommentRequest(@NotNull Long postId, Long parentId, @NotBlank String content) {
}

