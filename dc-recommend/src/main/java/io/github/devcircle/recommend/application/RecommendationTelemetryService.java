package io.github.devcircle.recommend.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.recommend.model.ExposureRequest;
import io.github.devcircle.recommend.model.FeedItem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns recommendation write-side telemetry: exposure, impression and event
 * persistence. Read-side ranking stays in {@link FeedService}; this service
 * deliberately receives the effective fallback policy from that facade so
 * test and production construction paths cannot silently diverge.
 */
@Service
public class RecommendationTelemetryService {
    private final StringRedisTemplate redisTemplate;
    private final RecommendationImpressionRepository impressionRepository;

    public RecommendationTelemetryService(StringRedisTemplate redisTemplate,
                                          RecommendationImpressionRepository impressionRepository) {
        this.redisTemplate = redisTemplate;
        this.impressionRepository = impressionRepository;
    }

    public int recordExposure(ExposureRequest request,
                              String idempotencyKey,
                              String normalizedScene,
                              boolean readFallbackEnabled) {
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
        requireRepository();
        int recorded = impressionRepository.saveExposure(
                request.userId(), request.postIds(), normalizedScene, idempotencyKey);
        try {
            String key = "user:" + request.userId() + ":exposed-posts";
            String[] ids = request.postIds().stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, ids);
            redisTemplate.expire(key, Duration.ofDays(7));
        } catch (RuntimeException ex) {
            failIfStrict("推荐曝光缓存暂时不可用", ex, readFallbackEnabled);
        }
        return recorded;
    }

    public Map<String, Object> recordRecommendEvent(Long userId,
                                                     Long postId,
                                                     String eventType,
                                                     String normalizedScene,
                                                     String experimentId,
                                                     String bucket,
                                                     String requestId,
                                                     boolean readFallbackEnabled) {
        requireWrite(userId, requestId);
        if (postId == null || postId <= 0) {
            throw validation("推荐事件内容 ID 必须为正整数");
        }
        String normalizedEvent = normalizeEventType(eventType);
        String finalExperimentId = experimentId == null || experimentId.isBlank()
                ? "exp-theme-feed-v1" : experimentId;
        String finalBucket = bucket == null || bucket.isBlank() ? "control" : bucket;
        requireRepository();
        impressionRepository.saveRecommendEvent(
                userId, postId, normalizedEvent, normalizedScene,
                finalExperimentId, finalBucket, requestId);
        try {
            redisTemplate.opsForHash().increment(
                    "recommend:event:" + normalizedScene, normalizedEvent, 1);
        } catch (RuntimeException ex) {
            failIfStrict("推荐事件缓存暂时不可用", ex, readFallbackEnabled);
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

    public void recordImpressions(Long userId,
                                  String scene,
                                  List<FeedItem> page,
                                  boolean readFallbackEnabled) {
        if (page == null || page.isEmpty()) {
            return;
        }
        if (page.size() == 1) {
            recordImpressionsIndividually(userId, scene, page, readFallbackEnabled);
            return;
        }
        try {
            String key = "recommend:impression:" + userId + ":" + scene;
            byte[] serializedKey = redisTemplate.getStringSerializer().serialize(key);
            byte[][] values = page.stream()
                    .map(item -> item.post().id() + "|" + item.algorithm() + "|" + item.score())
                    .map(redisTemplate.getStringSerializer()::serialize)
                    .toArray(byte[][]::new);
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.listCommands().lPush(serializedKey, values);
                connection.listCommands().lTrim(serializedKey, 0, 199);
                connection.keyCommands().expire(serializedKey, Duration.ofDays(7));
                return null;
            });
        } catch (RuntimeException ex) {
            failIfStrict("推荐曝光缓存暂时不可用", ex, readFallbackEnabled);
        }
        try {
            if (impressionRepository != null) {
                impressionRepository.saveAll(userId, scene, page);
            }
        } catch (RuntimeException ex) {
            failIfStrict("推荐曝光记录暂时不可用", ex, readFallbackEnabled);
        }
    }

    private void recordImpressionsIndividually(Long userId,
                                               String scene,
                                               List<FeedItem> page,
                                               boolean readFallbackEnabled) {
        for (FeedItem item : page) {
            try {
                String key = "recommend:impression:" + userId + ":" + scene;
                redisTemplate.opsForList().leftPush(
                        key, item.post().id() + "|" + item.algorithm() + "|" + item.score());
                redisTemplate.opsForList().trim(key, 0, 199);
                redisTemplate.expire(key, Duration.ofDays(7));
            } catch (RuntimeException ex) {
                failIfStrict("推荐曝光缓存暂时不可用", ex, readFallbackEnabled);
            }
            try {
                if (impressionRepository != null) {
                    impressionRepository.save(userId, scene, item);
                }
            } catch (RuntimeException ex) {
                failIfStrict("推荐曝光记录暂时不可用", ex, readFallbackEnabled);
            }
        }
    }

    private void requireRepository() {
        if (impressionRepository == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "推荐数据服务暂时不可用");
        }
    }

    private void requireWrite(Long userId, String idempotencyKey) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private void failIfStrict(String message, RuntimeException cause, boolean readFallbackEnabled) {
        if (!readFallbackEnabled) {
            throw dependency(message, cause);
        }
    }

    private BusinessException dependency(String message, RuntimeException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private String stableEventId(Long userId, String requestId) {
        return UUID.nameUUIDFromBytes(("recommend-event\u0000" + userId + "\u0000" + requestId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "detail_view";
        }
        String value = eventType.toLowerCase();
        if (List.of("click", "like", "favorite", "answer", "detail_view", "share").contains(value)) {
            return value;
        }
        return "detail_view";
    }
}
