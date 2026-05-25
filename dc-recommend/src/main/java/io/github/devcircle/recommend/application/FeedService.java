package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final LightweightMlRanker mlRanker;
    private final StringRedisTemplate redisTemplate;

    private final List<PostSummary> seedPosts = List.of(
            new PostSummary(1001L, "TEXT", "Spring Cloud 微服务里的 Outbox 可靠事件实践", "用本地事务、Kafka 和消费端幂等替代 XA。", "lin", List.of("Spring Cloud", "Kafka", "MySQL"), List.of(), 328, 42, 9150, "PUBLISHED", Instant.now().minusSeconds(7200)),
            new PostSummary(1002L, "IMAGE", "Elasticsearch 搜索索引设计图解", "用多图说明字段映射、标签过滤、排序和补偿重建策略。", "chen", List.of("Elasticsearch", "搜索"), List.of(
                    new MediaAssetView(201L, "demo/es-1.webp", "https://picsum.photos/seed/es1/720/960", "image/webp", 120000, 720, 960, null, "PENDING_REVIEW"),
                    new MediaAssetView(202L, "demo/es-2.webp", "https://picsum.photos/seed/es2/720/840", "image/webp", 118000, 720, 840, null, "PENDING_REVIEW")
            ), 211, 27, 6020, "PUBLISHED", Instant.now().minusSeconds(3600)),
            new PostSummary(1003L, "VIDEO", "Kafka 事件驱动通知系统演示", "纵向视频讲解站内信、WebSocket 和重复消费处理。", "wang", List.of("Kafka", "WebSocket"), List.of(
                    new MediaAssetView(301L, "demo/kafka-video.mp4", "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4", "video/mp4", 1200000, 720, 1280, 18, "PENDING_REVIEW")
            ), 97, 14, 3100, "PUBLISHED", Instant.now().minusSeconds(900))
    );

    public FeedService(RecommendationScorer scorer, LightweightMlRanker mlRanker, StringRedisTemplate redisTemplate) {
        this.scorer = scorer;
        this.mlRanker = mlRanker;
        this.redisTemplate = redisTemplate;
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feed(userId, cursor, size, "home");
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size, String scene) {
        return feedFromPosts(userId, seedPosts, cursor, size, scene);
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size) {
        return feedFromPosts(userId, candidates, cursor, size, "home");
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size, String scene) {
        int offset = parseCursor(cursor);
        Set<String> interests = interests(userId);
        Set<Long> exposed = exposed(userId);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> scene == null || scene.isBlank() || "home".equalsIgnoreCase(scene) || post.type().equalsIgnoreCase(scene))
                .map(post -> toFeedItem(userId, post, interests, exposed, scene))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
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
            return new FeedItem(post, reason(post, interests, hotScore, followedAuthor, mutualFollowees), ranked.score(), false, false, "LOCAL_LINEAR_MODEL", post.type(), ranked.features());
        } catch (RuntimeException ex) {
            double score = scorer.score(post, interests, hotScore, followedAuthor, alreadyExposed);
            return new FeedItem(post, scorer.reason(post, interests, hotScore, followedAuthor), score, false, false, "RULE_FALLBACK", post.type(), Map.of());
        }
    }

    private String reason(PostSummary post, Set<String> interests, double hotScore, boolean followedAuthor, long mutualFollowees) {
        if (followedAuthor) return "来自你关注的作者";
        if (mutualFollowees > 0) return "你们有共同关注，图谱亲密度较高";
        if (post.tags().stream().anyMatch(interests::contains)) return "与你最近关注的标签相关";
        if (hotScore > 20 || post.likes() > 100) return "近期互动热度较高";
        return "本地轻量模型探索推荐";
    }

    private Set<String> interests(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":interest-tags");
            if (values != null && !values.isEmpty()) return values;
        } catch (RuntimeException ignored) {
        }
        return Set.of("Spring Cloud", "Kafka", "Redis");
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
            return "lin".equals(author) && userId == 1L;
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
