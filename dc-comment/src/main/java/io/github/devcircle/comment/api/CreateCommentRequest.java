package io.github.devcircle.comment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(@NotNull Long postId, Long parentId, @NotBlank @Size(max = 2000) String content) {
}
