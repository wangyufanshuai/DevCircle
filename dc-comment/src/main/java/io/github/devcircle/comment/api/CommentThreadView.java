package io.github.devcircle.comment.api;

import java.util.List;

public record CommentThreadView(CommentView root, List<CommentView> replies) {
}
