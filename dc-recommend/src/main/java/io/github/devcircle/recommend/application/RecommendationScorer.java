package io.github.devcircle.recommend.application;

import io.github.devcircle.content.contract.PostSummary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class RecommendationScorer {
    public ScoreResult rank(PostSummary post, Set<String> interests, double hotScore, boolean followedAuthor, boolean exposed) {
        boolean interestMatch = interests.stream().anyMatch(value -> value.equalsIgnoreCase(post.theme()))
                || post.tags().stream().anyMatch(interests::contains);
        double interest = interestMatch ? 35.0 : 0.0;
        double follow = followedAuthor ? 20.0 : 0.0;
        double engagement = Math.max(0, post.likes()) + Math.max(0, post.comments()) * 2.0 + Math.max(0, hotScore);
        double quality = Math.min(1.0, Math.log1p(engagement) / Math.log(501.0)) * 20.0;
        long ageHours = Math.max(0, Duration.between(post.createdAt(), Instant.now()).toHours());
        double freshness = Math.exp(-ageHours / 168.0) * 15.0;
        double exploration = (Math.floorMod(post.id() * 31L, 101L) / 100.0) * 10.0;
        double penalty = exposed ? 100.0 : 0.0;

        Map<String, Double> features = new LinkedHashMap<>();
        features.put("themeInterest35", interest);
        features.put("followedAuthor20", follow);
        features.put("qualityHotness20", quality);
        features.put("freshness15", freshness);
        features.put("exploration10", exploration);
        features.put("exposurePenalty", penalty);
        return new ScoreResult(interest + follow + quality + freshness + exploration - penalty,
                reason(post, interestMatch, followedAuthor, quality, freshness), Map.copyOf(features));
    }

    public double score(PostSummary post, Set<String> interestTags, double hotScore, boolean followedAuthor, boolean exposed) {
        return rank(post, interestTags, hotScore, followedAuthor, exposed).score();
    }

    public String reason(PostSummary post, Set<String> interestTags, double hotScore, boolean followedAuthor) {
        return rank(post, interestTags, hotScore, followedAuthor, false).reason();
    }

    private String reason(PostSummary post, boolean interestMatch, boolean followedAuthor, double quality, double freshness) {
        if (interestMatch) return "与你近期感兴趣的主题相关";
        if (followedAuthor) return "来自你关注的创作者";
        if (quality >= 12) return "近期读者互动与内容质量较高";
        if (freshness >= 10) return "近期发布的新内容";
        return switch (post.theme()) {
            case "SCI_FI" -> "科幻主题中的探索推荐";
            case "COSMOS" -> "宇宙档案中的探索推荐";
            default -> "推理馆中的探索推荐";
        };
    }

    public record ScoreResult(double score, String reason, Map<String, Double> features) {
    }
}
