package io.github.devcircle.content.api;

import io.github.devcircle.content.contract.PostSummary;

import java.util.List;

public record PostCursorPage(List<PostSummary> records, String nextCursor, boolean hasMore) {
}
