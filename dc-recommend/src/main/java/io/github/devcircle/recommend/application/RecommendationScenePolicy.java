package io.github.devcircle.recommend.application;

import io.github.devcircle.content.contract.PostSummary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Centralizes the public recommendation scene vocabulary and its content mapping. */
@Component
public class RecommendationScenePolicy {
    private static final Set<String> SUPPORTED_SCENES = Set.of(
            "home", "mixed", "deduction", "sci_fi", "cosmos", "image", "video");
    private static final Set<String> THEME_SCENES = Set.of("deduction", "sci_fi", "cosmos");

    public String normalize(String scene) {
        if (scene == null || scene.isBlank()) {
            return "mixed";
        }
        String normalized = scene.toLowerCase(Locale.ROOT);
        return SUPPORTED_SCENES.contains(normalized) ? normalized : "mixed";
    }

    public String queryTheme(String scene) {
        String normalized = normalize(scene);
        return THEME_SCENES.contains(normalized) ? theme(normalized) : null;
    }

    public String theme(String scene) {
        return switch (normalize(scene)) {
            case "sci_fi" -> "SCI_FI";
            case "cosmos" -> "COSMOS";
            default -> "DEDUCTION";
        };
    }

    public boolean matches(PostSummary post, String scene) {
        String normalized = normalize(scene);
        if ("mixed".equals(normalized) || "home".equals(normalized)) {
            return true;
        }
        if ("image".equals(normalized) || "video".equals(normalized)) {
            return post.type().equalsIgnoreCase(normalized);
        }
        return post.theme().equalsIgnoreCase(theme(normalized));
    }
}
