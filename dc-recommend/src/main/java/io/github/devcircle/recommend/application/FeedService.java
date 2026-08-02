package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final StringRedisTemplate redisTemplate;
    private final RecommendationImpressionRepository impressionRepository;
    private final FollowSignalStore followSignalStore;
    private final RestClient contentClient;
    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    @Value("${devcircle.recommend.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

    public FeedService(RecommendationScorer scorer, StringRedisTemplate redisTemplate) {
        this(scorer, redisTemplate, null, "http://localhost:8103", null);
        this.readFallbackEnabled = true;
    }

    public FeedService(RecommendationScorer scorer,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       String contentBaseUrl) {
        this(scorer, redisTemplate, impressionRepository, contentBaseUrl, null);
    }

    @Autowired
    public FeedService(RecommendationScorer scorer,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl,
                       FollowSignalStore followSignalStore) {
        this.scorer = scorer;
        this.redisTemplate = redisTemplate;
        this.impressionRepository = impressionRepository;
        this.followSignalStore = followSignalStore;
        this.contentClient = RestClient.builder().baseUrl(contentBaseUrl).build();
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feed(userId, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size, String scene) {
        int safeSize = safePageSize(size);
        CandidateSet candidates = loadCandidates(scene, Math.max(safeSize * 4, 80));
        CursorPage<FeedItem> page = feedFromPosts(userId, candidates.posts(), cursor, safeSize, scene, candidates.source(), candidates.degraded());
        if (readFallbackEnabled && page.records().isEmpty() && !"SEED_FALLBACK".equals(candidates.source())) {
            return feedFromPosts(userId, seedPosts, cursor, safeSize, scene, "SEED_FALLBACK", true);
        }
        return page;
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size) {
        return feedFromPosts(userId, candidates, cursor, size, "mixed", "DB", false);
    }

    public CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size, String scene) {
        return feedFromPosts(userId, candidates, cursor, size, scene, "DB", false);
    }

    private CursorPage<FeedItem> feedFromPosts(Long userId, List<PostSummary> candidates, String cursor, int size, String scene, String source, boolean degraded) {
        int safeSize = safePageSize(size);
        int offset = parseCursor(cursor);
        String normalizedScene = normalizeScene(scene);
        Set<String> interests = interests(userId);
        Set<Long> followedAuthors = followedAuthors(userId);
        Set<Long> exposed = exposed(userId);
        ExperimentContext experiment = experiment(userId, normalizedScene);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> matchesScene(post, normalizedScene))
                .map(post -> toFeedItem(userId, post, interests, followedAuthors, exposed, normalizedScene, experiment))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
        if (ranked.isEmpty() && !exposed.isEmpty()) {
            degraded = true;
            ranked = candidates.stream()
                    .filter(post -> matchesScene(post, normalizedScene))
                    .map(post -> toFeedItem(userId, post, interests, followedAuthors, Set.of(), normalizedScene, experiment))
                    .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                    .toList();
        }
        List<FeedItem> page = ranked.stream().skip(offset).limit(safeSize).toList();
        recordImpressions(userId, normalizedScene, page);
        int nextOffset = offset + page.size();
        return CursorPage.of(page, String.valueOf(nextOffset), nextOffset < ranked.size(), degraded, source);
    }

    public int recordExposure(ExposureRequest request) {
        return recordExposure(request, "internal-" + UUID.randomUUID());
    }

    public int recordExposure(ExposureRequest request, String idempotencyKey) {
        requireWrite(request == null ? null : request.userId(), idempotencyKey);
        if (request == null) {
            throw validation("曝光请求不能为空");
        }
        if (request.postIds() == null || request.postIds().isEmpty()) {
            return 0;
        }
        if (request.postIds().stream().anyMatch(postId -> postId == null || postId <= 0)) {
            throw validation("曝光内容 ID 必须为正整数");
        }
        if (impressionRepository == null) {
            throw new io.github.devcircle.common.error.BusinessException(
                    io.github.devcircle.common.error.ErrorCode.DEPENDENCY_UNAVAILABLE, "推荐数据服务暂时不可用");
        }
        int recorded = impressionRepository.saveExposure(request.userId(), request.postIds(), normalizeScene(request.scene()), idempotencyKey);
        try {
            String key = "user:" + request.userId() + ":exposed-posts";
            String[] ids = request.postIds().stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, ids);
            redisTemplate.expire(key, Duration.ofDays(7));
        } catch (RuntimeException ex) {
            failIfStrict("推荐曝光缓存暂时不可用", ex);
        }
        return recorded;
    }

    public Map<String, Object> recordRecommendEvent(Long userId, Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
        requireWrite(userId, requestId);
        if (postId == null || postId <= 0) {
            throw validation("推荐事件内容 ID 必须为正整数");
        }
        String normalizedEvent = normalizeEventType(eventType);
        String normalizedScene = normalizeScene(scene);
        String finalExperimentId = experimentId == null || experimentId.isBlank() ? "exp-theme-feed-v1" : experimentId;
        String finalBucket = bucket == null || bucket.isBlank() ? "control" : bucket;
        if (impressionRepository == null) {
            throw new io.github.devcircle.common.error.BusinessException(
                    io.github.devcircle.common.error.ErrorCode.DEPENDENCY_UNAVAILABLE, "推荐数据服务暂时不可用");
        }
        impressionRepository.saveRecommendEvent(userId, postId, normalizedEvent, normalizedScene, finalExperimentId, finalBucket, requestId);
        try {
            redisTemplate.opsForHash().increment("recommend:event:" + normalizedScene, normalizedEvent, 1);
        } catch (RuntimeException ex) {
            failIfStrict("推荐事件缓存暂时不可用", ex);
        }
        return Map.of(
                "eventId", stableEventId(userId, requestId),
                "userId", userId,
                "postId", postId,
                "eventType", normalizedEvent,
                "scene", normalizedScene,
                "experimentId", finalExperimentId,
                "bucket", finalBucket
        );
    }

    private CandidateSet loadCandidates(String scene, int size) {
        try {
            String theme = sceneToQueryTheme(normalizeScene(scene));
            ApiResponse<PageResponse<PostSummary>> response = contentClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/posts")
                            .queryParam("page", 1)
                            .queryParam("size", size)
                            .queryParamIfPresent("theme", java.util.Optional.ofNullable(theme))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response != null && response.data() != null && !response.data().records().isEmpty()) {
                return new CandidateSet(response.data().records(), "DB", false);
            }
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) {
                throw dependency("内容服务暂时不可用", ex);
            }
        }
        return readFallbackEnabled
                ? new CandidateSet(seedPosts, "SEED_FALLBACK", true)
                : new CandidateSet(List.of(), "DB", false);
    }

    private FeedItem toFeedItem(Long userId, PostSummary post, Set<String> interests, Set<Long> followedAuthors,
                                Set<Long> exposed, String scene, ExperimentContext experiment) {
        double hotScore = hotScore(post.id());
        boolean followedAuthor = post.authorId() != null && followedAuthors.contains(post.authorId());
        boolean alreadyExposed = exposed.contains(post.id());
        RecommendationScorer.ScoreResult ranked = scorer.rank(post, interests, hotScore, followedAuthor, alreadyExposed);
        return new FeedItem(post, ranked.reason(), ranked.score(), false, false, "EXPLAINABLE_RULE_V1",
                post.type(), ranked.features(), experiment.experimentId(), experiment.bucket(),
                recallSources(post, scene, interests, followedAuthor, hotScore));
    }

    private ExperimentContext experiment(Long userId, String scene) {
        if (impressionRepository != null) {
            Map<String, Object> value = impressionRepository.activeExperiment(scene, userId);
            return new ExperimentContext(String.valueOf(value.get("experimentId")), String.valueOf(value.get("bucket")));
        }
        return new ExperimentContext("exp-theme-feed-v1", Math.floorMod(userId.hashCode(), 100) < 50 ? "control" : "treatment");
    }

    private List<String> recallSources(PostSummary post, String scene, Set<String> interests, boolean followedAuthor, double hotScore) {
        ArrayList<String> sources = new ArrayList<>();
        if (post.theme().equalsIgnoreCase(sceneToTheme(scene))) sources.add("theme");
        if (post.tags().stream().anyMatch(interests::contains)) sources.add("tag");
        if (hotScore > 0 || post.likes() > 100) sources.add("hot");
        if (followedAuthor) sources.add("follow_author");
        if (!post.relatedPostIds().isEmpty()) sources.add("series");
        if (sources.isEmpty()) sources.add("cold_start");
        return sources;
    }

    private void recordImpressions(Long userId, String scene, List<FeedItem> page) {
        for (FeedItem item : page) {
            try {
                String key = "recommend:impression:" + userId + ":" + scene;
                redisTemplate.opsForList().leftPush(key, item.post().id() + "|" + item.algorithm() + "|" + item.score());
                redisTemplate.opsForList().trim(key, 0, 199);
                redisTemplate.expire(key, Duration.ofDays(7));
            } catch (RuntimeException ex) {
                failIfStrict("推荐曝光缓存暂时不可用", ex);
            }
            try {
                if (impressionRepository != null) impressionRepository.save(userId, scene, item);
            } catch (RuntimeException ex) {
                failIfStrict("推荐曝光记录暂时不可用", ex);
            }
        }
    }

    private boolean matchesScene(PostSummary post, String scene) {
        if ("mixed".equals(scene) || "home".equals(scene)) return true;
        if ("image".equals(scene) || "video".equals(scene)) return post.type().equalsIgnoreCase(scene);
        return post.theme().equalsIgnoreCase(sceneToTheme(scene));
    }

    private String sceneToQueryTheme(String scene) {
        if ("deduction".equals(scene) || "sci_fi".equals(scene) || "cosmos".equals(scene)) return sceneToTheme(scene);
        return null;
    }

    private String sceneToTheme(String scene) {
        return switch (scene) {
            case "sci_fi" -> "SCI_FI";
            case "cosmos" -> "COSMOS";
            default -> "DEDUCTION";
        };
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) return "mixed";
        String normalized = scene.toLowerCase();
        if (List.of("home", "mixed", "deduction", "sci_fi", "cosmos", "image", "video").contains(normalized)) return normalized;
        return "mixed";
    }

    private Set<String> interests(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":interest-tags");
            if (values != null && !values.isEmpty()) return values;
        } catch (RuntimeException ex) {
            failIfStrict("推荐兴趣数据暂时不可用", ex);
        }
        if (!readFallbackEnabled) return Set.of();
        return Set.of("密室", "世界观", "星云");
    }

    private Set<Long> exposed(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members("user:" + userId + ":exposed-posts");
            if (values != null) return values.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException ex) {
            failIfStrict("推荐曝光数据暂时不可用", ex);
        }
        return Set.of();
    }

    private Set<Long> followedAuthors(Long userId) {
        if (followSignalStore == null) return Set.of();
        try {
            return followSignalStore.followedAuthorIds(userId);
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) throw dependency("关注图谱暂时不可用", ex);
            return Set.of();
        }
    }

    private double hotScore(Long postId) {
        try {
            Double score = redisTemplate.opsForZSet().score("feed:hot-posts", postId.toString());
            return score == null ? 0.0 : score;
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) throw dependency("推荐热度暂时不可用", ex);
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

    private int safePageSize(int size) {
        return Math.max(1, Math.min(size, 50));
    }

    private void requireWrite(Long userId, String idempotencyKey) {
        if (userId == null || userId <= 0) {
            throw new io.github.devcircle.common.error.BusinessException(
                    io.github.devcircle.common.error.ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new io.github.devcircle.common.error.BusinessException(
                    io.github.devcircle.common.error.ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private void failIfStrict(String message, RuntimeException cause) {
        if (!readFallbackEnabled) throw dependency(message, cause);
    }

    private io.github.devcircle.common.error.BusinessException dependency(String message, RuntimeException cause) {
        var failure = new io.github.devcircle.common.error.BusinessException(
                io.github.devcircle.common.error.ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    private io.github.devcircle.common.error.BusinessException validation(String message) {
        return new io.github.devcircle.common.error.BusinessException(
                io.github.devcircle.common.error.ErrorCode.VALIDATION_FAILED, message);
    }

    private String stableEventId(Long userId, String requestId) {
        return UUID.nameUUIDFromBytes(("recommend-event\u0000" + userId + "\u0000" + requestId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) return "detail_view";
        String value = eventType.toLowerCase();
        if (List.of("click", "like", "favorite", "answer", "detail_view", "share").contains(value)) return value;
        return "detail_view";
    }

    private record CandidateSet(List<PostSummary> posts, String source, boolean degraded) {
    }

    private record ExperimentContext(String experimentId, String bucket) {
    }
}
