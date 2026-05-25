package io.github.devcircle.common.event;

import java.util.List;

public record PostCreatedPayload(Long postId, Long authorId, String title, List<String> tags, String theme, String contentKind) {
    public PostCreatedPayload(Long postId, Long authorId, String title, List<String> tags) {
        this(postId, authorId, title, tags, "DEDUCTION", "CASE_FILE");
    }
}
