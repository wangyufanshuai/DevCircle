package io.github.devcircle.comment.api;

import java.time.Instant;

public record CommentView(Long id,
                          Long postId,
                          Long parentId,
                          Long rootId,
                          Long authorId,
                          String author,
                          String content,
                          String status,
                          long likes,
                          Instant createdAt) {
}
