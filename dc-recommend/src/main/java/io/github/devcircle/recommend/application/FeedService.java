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
    private final LightweightMlRanker mlRanker;
    private final StringRedisTemplate redisTemplate;
    private final RecommendationImpressionRepository impressionRepository;
    private final RestClient contentClient;
    private final List<PostSummary> seedPosts = ThemeContentLibrary.summaries();

    public FeedService(RecommendationScorer scorer, LightweightMlRanker mlRanker, StringRedisTemplate redisTemplate) {
        this(scorer, mlRanker, redisTemplate, null, "http://localhost:8103");
    }

    @Autowired
    public FeedService(RecommendationScorer scorer,
                       LightweightMlRanker mlRanker,
                       StringRedisTemplate redisTemplate,
                       RecommendationImpressionRepository impressionRepository,
                       @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl) {
        this.scorer = scorer;
        this.mlRanker = mlRanker;
        this.redisTemplate = redisTemplate;
        this.impressionRepository = impressionRepository;
        this.contentClient = RestClient.builder().baseUrl(contentBaseUrl).build();
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size) {
        return feed(userId, cursor, size, "mixed");
    }

    public CursorPage<FeedItem> feed(Long userId, String cursor, int size, String scene) {
        CandidateSet candidates = loadCandidates(scene, Math.max(size * 4, 80));
        CursorPage<FeedItem> page = feedFromPosts(userId, candidates.posts(), cursor, size, scene, candidates.source(), candidates.degraded());
        if (page.records().isEmpty() && !"SEED_FALLBACK".equals(candidates.source())) {
            return feedFromPosts(userId, seedPosts, cursor, size, scene, "SEED_FALLBACK", true);
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
        int offset = parseCursor(cursor);
        String normalizedScene = normalizeScene(scene);
        Set<String> interests = interests(userId);
        Set<Long> exposed = exposed(userId);
        ExperimentContext experiment = experiment(userId, normalizedScene);
        List<FeedItem> ranked = candidates.stream()
                .filter(post -> matchesScene(post, normalizedScene))
                .map(post -> toFeedItem(userId, post, interests, exposed, normalizedScene, experiment))
                .filter(item -> item.score() > -100)
                .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                .toList();
        if (ranked.isEmpty() && !exposed.isEmpty()) {
            degraded = true;
            ranked = candidates.stream()
                    .filter(post -> matchesScene(post, normalizedScene))
                    .map(post -> toFeedItem(userId, post, interests, Set.of(), normalizedScene, experiment))
                    .sorted(Comparator.comparingDouble(FeedItem::score).reversed())
                    .toList();
        }
        List<FeedItem> page = ranked.stream().skip(offset).limit(size).toList();
        recordImpressions(userId, normalizedScene, page);
        int nextOffset = offset + page.size();
        return CursorPage.of(page, String.valueOf(nextOffset), nextOffset < ranked.size(), degraded, source);
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

    public Map<String, Object> recordRecommendEvent(Long userId, Long postId, String eventType, String scene, String experimentId, String bucket, String requestId) {
        String normalizedEvent = normalizeEventType(eventType);
        String normalizedScene = normalizeScene(scene);
        String finalExperimentId = experimentId == null || experimentId.isBlank() ? "exp-theme-feed-v1" : experimentId;
        String finalBucket = bucket == null || bucket.isBlank() ? "control" : bucket;
        if (impressionRepository != null) {
            impressionRepository.saveRecommendEvent(userId, postId, normalizedEvent, normalizedScene, finalExperimentId, finalBucket, requestId);
        }
        try {
            redisTemplate.opsForHash().increment("recommend:event:" + normalizedScene, normalizedEvent, 1);
        } catch (RuntimeException ignored) {
        }
        return Map.of(
                "eventId", UUID.randomUUID().toString(),
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
        } catch (RuntimeException ignored) {
        }
        return new CandidateSet(seedPosts, "SEED_FALLBACK", true);
    }

    private FeedItem toFeedItem(Long userId, PostSummary post, Set<String> interests, Set<Long> exposed, String scene, ExperimentContext experiment) {
        double hotScore = hotScore(post.id());
        boolean followedAuthor = followedAuthor(userId, post.author());
        boolean alreadyExposed = exposed.contains(post.id());
        long mutualFollowees = mutualFollowees(userId, post.author());
        try {
            var ranked = mlRanker.rank(post, interests, hotScore, followedAuthor, mutualFollowees, alreadyExposed, scene);
            double themeBoost = post.theme().equalsIgnoreCase(sceneToTheme(scene)) ? 18 : 0;
            double solveBoost = post.solvable() && post.solvedCount() > 20 ? 8 : 0;
            return new FeedItem(post, reason(post, interests, hotScore, followedAuthor, mutualFollowees), ranked.score() + themeBoost + solveBoost,
                    false, false, "THEME_LINEAR_MODEL", post.type(), ranked.features(), experiment.experimentId(), experiment.bucket(), recallSources(post, scene, interests, followedAuthor, hotScore));
        } catch (RuntimeException ex) {
            double score = scorer.score(post, interests, hotScore, followedAuthor, alreadyExposed);
            return new FeedItem(post, scorer.reason(post, interests, hotScore, followedAuthor), score,
                    false, false, "RULE_FALLBACK", post.type(), Map.of(), experiment.experimentId(), experiment.bucket(), recallSources(post, scene, interests, followedAuthor, hotScore));
        }
    }

    private String reason(PostSummary post, Set<String> interests, double hotScore, boolean followedAuthor, long mutualFollowees) {
        var seedReason = ThemeContentLibrary.find(post.id()).map(io.github.devcircle.common.seed.ThemeSeedPost::reason).orElse("");
        if (!seedReason.isBlank()) return seedReason;
        if (post.solvable()) return "可解谜案件，已有 " + post.solvedCount() + " 人完成推理";
        if (followedAuthor) return "来自你关注的创作者";
        if (mutualFollowees > 0) return "存在共同关注，社交图谱亲密度较高";
        if (post.tags().stream().anyMatch(interests::contains)) return "与你最近关注的主题标签相关";
        if (hotScore > 20 || post.likes() > 300) return "近期互动热度较高";
        return switch (post.theme()) {
            case "SCI_FI" -> "科幻设定与短篇创作精选";
            case "COSMOS" -> "宇宙档案与观测日志推荐";
            default -> "推理馆编辑精选";
        };
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
                if (impressionRepository != null) {
                    impressionRepository.save(userId, scene, item);
                }
            } catch (RuntimeException ignored) {
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
