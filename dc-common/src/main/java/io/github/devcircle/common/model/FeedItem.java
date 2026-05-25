package io.github.devcircle.common.model;

public record FeedItem(PostSummary post, String reason, double score, boolean liked, boolean favorited) {
}

