package io.github.devcircle.common.model;

import java.time.Instant;
import java.util.List;

public record PostSummary(
        Long id,
        String title,
        String excerpt,
        String author,
        List<String> tags,
        long likes,
        long comments,
        long views,
        String status,
        Instant createdAt
) {
}

