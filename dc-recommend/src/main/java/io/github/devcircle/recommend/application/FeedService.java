package io.github.devcircle.recommend.application;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.CursorPage;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.recommend.model.ExposureRequest;
import io.github.devcircle.recommend.model.FeedItem;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FeedService {
    private final RecommendationScorer scorer;
    private final StringRedisTemplate redisTemplate;
    private final RecommendationImpressionRepository impressionRepository;
    private final RecommendationTelemetryService telemetryService;
    private final RecommendationScenePolicy scenePolicy;
    private final FollowSignalStore followSignalStore;
    private final RestClient contentClient;
    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    @Value("${devcircle.recommend.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

    public FeedService(RecommendationScorer scorer, StringRedisTemplate redisTemplate) {
        this(scorer, redisTemplate, null, "http://localhost:8103", null,
                new RecommendationTelemetryService(redisTemplate, null), new RecommendationScenePolicy());
        this.readFallbackEnabled = true;
    }

    public FeedService(RecommendationScorer scorer,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       String contentBaseUrl) {
        this(scorer, redisTemplate, impressionRepository, contentBaseUrl, null,
                new RecommendationTelemetryService(redisTemplate, impressionRepository),
                new RecommendationScenePolicy());
    }

    public FeedService(RecommendationScorer scorer,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       String contentBaseUrl,
                       FollowSignalStore followSignalStore) {
        this(scorer, redisTemplate, impressionRepository, contentBaseUrl, followSignalStore,
                new RecommendationTelemetryService(redisTemplate, impressionRepository),
                new RecommendationScenePolicy());
    }

    @Autowired
    public FeedService(RecommendationScorer scorer,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl,
                       FollowSignalStore followSignalStore,
                       RecommendationTelemetryService telemetryService,
                       RecommendationScenePolicy scenePolicy) {
        this.scorer = scorer;
        this.redisTemplate = redisTemplate;
        this.impressionRepository = impressionRepository;
        this.telemetryService = telemetryService;
        this.scenePolicy = scenePolicy;
        this.followSignalStore = followSignalStore;
        this.contentClient = BoundedRestClientFactory.create(contentBaseUrl);
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
        String normalizedScene = scenePolicy.normalize(scene);
        Set<String> interests = interests(userId);
        Set<Long> followedAuthors = followedAuthors(userId);
        Set<Long> exposed = exposed(userId);
        ExperimentContext experiment = experiment(userId, normalizedScene);
        Map<Long, Double> hotScores = hotScores(candidates);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> scenePolicy.matches(post, normalizedScene))
                .map(post -> toFeedItem(post, interests, followedAuthors, exposed,
                        normalizedScene, experiment, hotScores))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
        if (ranked.isEmpty() && !exposed.isEmpty()) {
            degraded = true;
            ranked = candidates.stream()
                    .filter(post -> scenePolicy.matches(post, normalizedScene))
                    .map(post -> toFeedItem(post, interests, followedAuthors, Set.of(),
                            normalizedScene, experiment, hotScores))
                    .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                    .toList();
        }
        List<FeedItem> page = ranked.stream().skip(offset).limit(safeSize).toList();
        telemetryService.recordImpressions(userId, normalizedScene, page, readFallbackEnabled);
        int nextOffset = offset + page.size();
        return CursorPage.of(page, String.valueOf(nextOffset), nextOffset < ranked.size(), degraded, source);
    }

    public int recordExposure(ExposureRequest request) {
        return recordExposure(request, "internal-" + java.util.UUID.randomUUID());
    }

    public int recordExposure(ExposureRequest request, String idempotencyKey) {
        String scene = scenePolicy.normalize(request == null ? null : request.scene());
        return telemetryService.recordExposure(request, idempotencyKey, scene, readFallbackEnabled);
    }

    public Map<String, Object> recordRecommendEvent(Long userId, Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
        return telemetryService.recordRecommendEvent(
                userId, postId, eventType, scenePolicy.normalize(scene), experimentId, bucket,
                requestId, readFallbackEnabled);
    }

    private CandidateSet loadCandidates(String scene, int size) {
        try {
            String theme = scenePolicy.queryTheme(scene);
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

    private FeedItem toFeedItem(PostSummary post, Set<String> interests, Set<Long> followedAuthors,
                                Set<Long> exposed, String scene, ExperimentContext experiment,
                                Map<Long, Double> hotScores) {
        double hotScore = hotScores.getOrDefault(post.id(), 0.0);
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
        if (post.theme().equalsIgnoreCase(scenePolicy.theme(scene))) sources.add("theme");
        if (post.tags().stream().anyMatch(interests::contains)) sources.add("tag");
        if (hotScore > 0 || post.likes() > 100) sources.add("hot");
        if (followedAuthor) sources.add("follow_author");
        if (!post.relatedPostIds().isEmpty()) sources.add("series");
        if (sources.isEmpty()) sources.add("cold_start");
        return sources;
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

    private Map<Long, Double> hotScores(List<PostSummary> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        if (posts.size() == 1) {
            Long postId = posts.getFirst().id();
            return Map.of(postId, hotScore(postId));
        }
        try {
            Object[] postIds = posts.stream().map(post -> post.id().toString()).toArray();
            List<Double> scores = redisTemplate.opsForZSet().score("feed:hot-posts", postIds);
            Map<Long, Double> result = new LinkedHashMap<>();
            for (int index = 0; index < posts.size(); index++) {
                Double score = scores == null || index >= scores.size() ? null : scores.get(index);
                result.put(posts.get(index).id(), score == null ? 0.0 : score);
            }
            return result;
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) throw dependency("推荐热度数据暂时不可用", ex);
            return Map.of();
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

    private io.github.devcircle.common.error.BusinessException dependency(String message, RuntimeException cause) {
        var failure = new io.github.devcircle.common.error.BusinessException(
                io.github.devcircle.common.error.ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    private void failIfStrict(String message, RuntimeException cause) {
        if (!readFallbackEnabled) {
            throw dependency(message, cause);
        }
    }

    private record CandidateSet(List<PostSummary> posts, String source, boolean degraded) {
    }

    private record ExperimentContext(String experimentId, String bucket) {
    }
}
