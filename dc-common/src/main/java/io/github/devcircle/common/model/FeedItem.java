package io.github.devcircle.common.model;

import java.util.Map;

public record FeedItem(
        PostSummary post,
        String reason,
        double score,
        boolean liked,
        boolean favorited,
        String algorithm,
        String mediaType,
        Map<String, Double> features
) {
    public FeedItem(PostSummary post, String reason, double score, boolean liked, boolean favorited) {
        this(post, reason, score, liked, favorited, "RULE_FALLBACK", post.type(), Map.of());
    }
}
