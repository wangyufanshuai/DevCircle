package io.github.devcircle.common.api;

import java.util.List;

public record PageResponse<T>(List<T> records, long total, int page, int size) {
    public static <T> PageResponse<T> of(List<T> records, long total, int page, int size) {
        return new PageResponse<>(records, total, page, size);
    }
}

