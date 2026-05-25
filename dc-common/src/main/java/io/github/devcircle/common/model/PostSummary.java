package io.github.devcircle.common.model;

import java.time.Instant;
import java.util.List;

public record PostSummary(
        Long id,
        String type,
        String title,
        String excerpt,
        String author,
        List<String> tags,
        List<MediaAssetView> mediaAssets,
        long likes,
        long comments,
        long views,
        String status,
        Instant createdAt
) {
    public PostSummary(
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
        this(id, "TEXT", title, excerpt, author, tags, List.of(), likes, comments, views, status, createdAt);
    }
}
