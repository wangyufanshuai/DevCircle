package io.github.devcircle.common.event;

import java.util.List;

public record PostCreatedPayload(Long postId, Long authorId, String title, List<String> tags) {
}

