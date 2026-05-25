package io.github.devcircle.recommend.application;

import io.github.devcircle.common.model.PostSummary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
public class RecommendationScorer {
    public double score(PostSummary post, Set<String> interestTags, double hotScore, boolean followedAuthor, boolean exposed) {
        double tagScore = post.tags().stream().filter(interestTags::contains).count() * 20.0;
        double heatScore = Math.min(hotScore, 100.0) * 0.35 + post.likes() * 0.08 + post.comments() * 0.12;
        double freshnessScore = freshnessScore(post.createdAt());
        double followScore = followedAuthor ? 12.0 : 0.0;
        double exposurePenalty = exposed ? 1000.0 : 0.0;
        return tagScore + heatScore + freshnessScore + followScore - exposurePenalty;
    }

    public String reason(PostSummary post, Set<String> interestTags, double hotScore, boolean followedAuthor) {
        if (post.tags().stream().anyMatch(interestTags::contains)) {
            return "与你最近关注的标签相关";
        }
        if (followedAuthor) {
            return "来自你关注的作者";
        }
        if (hotScore > 20 || post.likes() > 100) {
            return "近期互动热度较高";
        }
        return "新内容探索推荐";
    }

    private double freshnessScore(Instant createdAt) {
        long hours = Math.max(1, Duration.between(createdAt, Instant.now()).toHours());
        return Math.max(0, 24.0 - hours) * 0.6;
    }
}
