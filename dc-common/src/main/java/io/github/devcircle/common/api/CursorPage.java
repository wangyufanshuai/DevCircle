package io.github.devcircle.common.api;

import java.util.List;

public record CursorPage<T>(List<T> records, String nextCursor, boolean hasMore, boolean degraded, String source) {
    public static <T> CursorPage<T> of(List<T> records, String nextCursor, boolean hasMore) {
        return new CursorPage<>(records, nextCursor, hasMore, false, "DB");
    }

    public static <T> CursorPage<T> of(List<T> records, String nextCursor, boolean hasMore, boolean degraded, String source) {
        return new CursorPage<>(records, nextCursor, hasMore, degraded, source);
    }
}
