package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final LightweightMlRanker mlRanker;
    private final StringRedisTemplate redisTemplate;

    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    public FeedService(RecommendationScorer scorer, LightweightMlRanker mlRanker, StringRedisTemplate redisTemplate) {
        this.scorer = scorer;
        this.mlRanker = mlRanker;
        this.redisTemplate = redisTemplate;
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feed(userId, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size, String scene) {
        return feedFromPosts(userId, seedPosts, cursor, size, scene);
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size) {
        return feedFromPosts(userId, candidates, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size, String scene) {
        int offset = parseCursor(cursor);
        String normalizedScene = normalizeScene(scene);
        Set<String> interests = interests(userId);
        Set<Long> exposed = exposed(userId);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> matchesScene(post, normalizedScene))
                .map(post -> toFeedItem(userId, post, interests, exposed, normalizedScene))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
        if (ranked.isEmpty() && !exposed.isEmpty()) {
            ranked = candidates.stream()
                    .filter(post -> matchesScene(post, normalizedScene))
                    .map(post -> toFeedItem(userId, post, interests, Set.of(), normalizedScene))
                    .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                    .toList();
        }
        List<FeedItem> page = ranked.stream().skip(offset).limit(size).toList();
        int nextOffset = offset + page.size();
        return CursorPage.of(page, String.valueOf(nextOffset), nextOffset < ranked.size());
    }

    public int recordExposure(ExposureRequest request) {
        if (request.postIds() == null || request.postIds().isEmpty()) {
            return 0;
        }
        try {
            String key = "user:" + request.userId() + ":exposed-posts";
            String[] ids = request.postIds().stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, ids);
            redisTemplate.expire(key, Duration.ofDays(7));
        } catch (RuntimeException ignored) {
        }
        return request.postIds().size();
    }

    private FeedItem toFeedItem(Long userId, PostSummary post, Set<String> interests, Set<Long> exposed, String scene) {
        double hotScore = hotScore(post.id());
        boolean followedAuthor = followedAuthor(userId, post.author());
        boolean alreadyExposed = exposed.contains(post.id());
        long mutualFollowees = mutualFollowees(userId, post.author());
        try {
            var ranked = mlRanker.rank(post, interests, hotScore, followedAuthor, mutualFollowees, alreadyExposed, scene);
            double themeBoost = post.theme().equalsIgnoreCase(sceneToTheme(scene)) ? 18 : 0;
            double solveBoost = post.solvable() && post.solvedCount() > 20 ? 8 : 0;
            return new FeedItem(post, reason(post, interests, hotScore, followedAuthor, mutualFollowees), ranked.score() + themeBoost + solveBoost, false, false, "THEME_LINEAR_MODEL", post.type(), ranked.features());
        } catch (RuntimeException ex) {
            double score = scorer.score(post, interests, hotScore, followedAuthor, alreadyExposed);
            return new FeedItem(post, scorer.reason(post, interests, hotScore, followedAuthor), score, false, false, "RULE_FALLBACK", post.type(), Map.of());
        }
    }

    private String reason(PostSummary post, Set<String> interests, double hotScore, boolean followedAuthor, long mutualFollowees) {
        var seedReason = ThemeContentLibrary.find(post.id()).map(io.github.devcircle.common.seed.ThemeSeedPost::reason).orElse("");
        if (!seedReason.isBlank()) return seedReason;
        if (post.solvable()) return "可解谜案件，已有 " + post.solvedCount() + " 人完成推理";
        if (followedAuthor) return "来自你关注的创作者";
        if (mutualFollowees > 0) return "你们有共同关注，图谱亲密度较高";
        if (post.tags().stream().anyMatch(interests::contains)) return "与你最近关注的主题标签相关";
        if (hotScore > 20 || post.likes() > 300) return "近期互动热度较高";
        return switch (post.theme()) {
            case "SCI_FI" -> "科幻设定与短篇创作精选";
            case "COSMOS" -> "宇宙档案与观测日志推荐";
            default -> "推理馆编辑精选";
        };
    }

    private boolean matchesScene(PostSummary post, String scene) {
        if ("mixed".equals(scene) || "home".equals(scene)) {
            return true;
        }
        if ("image".equals(scene) || "video".equals(scene)) {
            return post.type().equalsIgnoreCase(scene);
        }
        return post.theme().equalsIgnoreCase(sceneToTheme(scene));
    }

    private String sceneToTheme(String scene) {
        return switch (scene) {
            case "sci_fi" -> "SCI_FI";
            case "cosmos" -> "COSMOS";
            default -> "DEDUCTION";
        };
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "mixed";
        }
        String normalized = scene.toLowerCase();
        if (List.of("home", "mixed", "deduction", "sci_fi", "cosmos", "image", "video").contains(normalized)) {
            return normalized;
        }
        return "mixed";
    }

    private Set<String> interests(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":interest-tags");
            if (values != null && !values.isEmpty()) return values;
        } catch (RuntimeException ignored) {
        }
        return Set.of("密室", "世界观", "星云");
    }

    private Set<Long> exposed(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":exposed-posts");
            if (values != null) return values.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException ignored) {
        }
        return Set.of();
    }

    private boolean followedAuthor(Long userId, String author) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("user:" + userId + ":followees", author));
        } catch (RuntimeException ignored) {
            return "林墨".equals(author) && userId == 1L;
        }
    }

    private long mutualFollowees(Long userId, String author) {
        try {
            String value = (String) redisTemplate.opsForHash().get("user:" + userId + ":graph-features", "mutual:" + author);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private double hotScore(Long postId) {
        try {
            Double score = redisTemplate.opsForZSet().score("feed:hot-posts", postId.toString());
            return score == null ? 0.0 : score;
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

}
