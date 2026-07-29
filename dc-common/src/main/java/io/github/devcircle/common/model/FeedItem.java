package io.github.devcircle.common.model;

import java.util.Map;
import java.util.List;

public record FeedItem(
        PostSummary post,
        String reason,
        double score,
        boolean liked,
        boolean favorited,
        String algorithm,
        String mediaType,
        Map<String, Double> features,
        String experimentId,
        String bucket,
        List<String> recallSources
) {
    public FeedItem(PostSummary post, String reason, double score, boolean liked, boolean favorited) {
        this(post, reason, score, liked, favorited, "RULE_V2", post.type(), Map.of(), "exp-default", "control", List.of("cold_start"));
    }

    public FeedItem(PostSummary post, String reason, double score, boolean liked, boolean favorited, String algorithm, String mediaType, Map<String, Double> features) {
        this(post, reason, score, liked, favorited, algorithm, mediaType, features, "exp-default", "control", List.of("cold_start"));
    }
}
