package io.github.devcircle.comment.api;

import java.time.Instant;

public record CommentView(Long id, Long postId, Long parentId, String author, String content, String status, Instant createdAt) {
}

