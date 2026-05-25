package io.github.devcircle.recommend.application;

import io.github.devcircle.common.model.PostSummary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class LightweightMlRanker {
    public RankedScore rank(PostSummary post, Set<String> interestTags, double hotScore, boolean followedAuthor, long mutualFollowees, boolean exposed, String scene) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("tagMatch", post.tags().stream().filter(interestTags::contains).count() / Math.max(1.0, post.tags().size()));
        features.put("hot", Math.min(hotScore + post.likes() * 0.8 + post.comments() * 1.2, 500.0) / 500.0);
        features.put("freshness", Math.max(0.0, 48.0 - Duration.between(post.createdAt(), Instant.now()).toHours()) / 48.0);
        features.put("followedAuthor", followedAuthor ? 1.0 : 0.0);
        features.put("mutualFollowees", Math.min(mutualFollowees, 10.0) / 10.0);
        features.put("mediaSceneMatch", sceneMatches(post.type(), scene) ? 1.0 : 0.0);
        features.put("exposed", exposed ? 1.0 : 0.0);

        double score = 100.0
                * (features.get("tagMatch") * 0.30
                + features.get("hot") * 0.18
                + features.get("freshness") * 0.16
                + features.get("followedAuthor") * 0.14
                + features.get("mutualFollowees") * 0.12
                + features.get("mediaSceneMatch") * 0.10
                - features.get("exposed") * 2.0);
        return new RankedScore(score, features);
    }

    private boolean sceneMatches(String mediaType, String scene) {
        if (scene == null || scene.isBlank() || "home".equalsIgnoreCase(scene)) {
            return true;
        }
        return mediaType.equalsIgnoreCase(scene);
    }

    public record RankedScore(double score, Map<String, Double> features) {
    }
}
