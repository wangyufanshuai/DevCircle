package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.PostSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final StringRedisTemplate redisTemplate;

    private final List<PostSummary> seedPosts = List.of(
            new PostSummary(1001L, "Spring Cloud 微服务里的 Outbox 可靠事件实践", "用本地事务、Kafka 和消费端幂等替代 XA，保证内容发布、搜索索引和通知推送最终一致。", "lin", List.of("Spring Cloud", "Kafka", "MySQL"), 328, 42, 9150, "PUBLISHED", Instant.now().minusSeconds(7200)),
            new PostSummary(1002L, "Elasticsearch 技术社区搜索索引怎么设计", "字段映射、标签过滤、排序和补偿重建策略。", "chen", List.of("Elasticsearch", "搜索"), 211, 27, 6020, "PENDING", Instant.now().minusSeconds(3600)),
            new PostSummary(1003L, "Redis 热点缓存、限流和点赞幂等的工程边界", "从详情缓存、布隆过滤、令牌桶到互动服务的幂等键设计。", "zhou", List.of("Redis", "限流"), 156, 18, 3980, "PUBLISHED", Instant.now().minusSeconds(1800)),
            new PostSummary(1004L, "Kafka 事件驱动通知系统设计", "站内信、WebSocket、离线落库和重复消费处理。", "wang", List.of("Kafka", "WebSocket"), 97, 14, 3100, "PUBLISHED", Instant.now().minusSeconds(900))
    );

    public FeedService(RecommendationScorer scorer, StringRedisTemplate redisTemplate) {
        this.scorer = scorer;
        this.redisTemplate = redisTemplate;
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feedFromPosts(userId, seedPosts, cursor, size);
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size) {
        int offset = parseCursor(cursor);
        Set<String> interests = interests(userId);
        Set<Long> exposed = exposed(userId);
        List<FeedItem> ranked = candidates.stream()
                .map(post -> toFeedItem(userId, post, interests, exposed))
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
            // Exposure memory is best-effort; Feed still works without Redis.
        }
        return request.postIds().size();
    }

    private FeedItem toFeedItem(Long userId, PostSummary post, Set<String> interests, Set<Long> exposed) {
        double hotScore = hotScore(post.id());
        boolean followedAuthor = "lin".equals(post.author()) && userId == 1L;
        boolean alreadyExposed = exposed.contains(post.id());
        double score = scorer.score(post, interests, hotScore, followedAuthor, alreadyExposed);
        return new FeedItem(post, scorer.reason(post, interests, hotScore, followedAuthor), score, false, false);
    }

    private Set<String> interests(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":interest-tags");
            if (values != null && !values.isEmpty()) {
                return values;
            }
        } catch (RuntimeException ignored) {
        }
        return Set.of("Spring Cloud", "Kafka", "Redis");
    }

    private Set<Long> exposed(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":exposed-posts");
            if (values != null) {
                return values.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
            }
        } catch (RuntimeException ignored) {
        }
        return Set.of();
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
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
