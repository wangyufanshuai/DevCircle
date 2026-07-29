package io.github.devcircle.content.api;

import io.github.devcircle.common.model.PostSummary;

import java.util.List;

public record PostCursorPage(List<PostSummary> records, String nextCursor, boolean hasMore) {
}
