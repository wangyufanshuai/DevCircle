package io.github.devcircle.interaction.application;

import io.github.devcircle.interaction.api.InteractionResult;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
@Service
public class InteractionService {
    private final InteractionRepository interactionRepository;
    private final StringRedisTemplate redisTemplate;

    public InteractionService(InteractionRepository interactionRepository, StringRedisTemplate redisTemplate) {
        this.interactionRepository = interactionRepository;
        this.redisTemplate = redisTemplate;
    }

    public InteractionResult like(Long userId, Long postId, String idempotencyKey) {
        return interact(userId, postId, "LIKE", idempotencyKey);
    }

    public InteractionResult favorite(Long userId, Long postId, String idempotencyKey) {
        return interact(userId, postId, "FAVORITE", idempotencyKey);
    }

    private InteractionResult interact(Long userId, Long targetId, String type, String idempotencyKey) {
        String key = normalizeKey(type, targetId, idempotencyKey);
        InteractionRepository.InteractionWriteResult result = "LIKE".equals(type)
                ? interactionRepository.insertLike(userId, targetId, key)
                : interactionRepository.insertFavorite(userId, targetId, key);
        writeRedisSignals(userId, targetId, type, result.inserted());
        return new InteractionResult(targetId, type, true, result.duplicateRequest() || !result.inserted(), key);
    }

    private String normalizeKey(String type, Long targetId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        throw new io.github.devcircle.common.error.BusinessException(
                io.github.devcircle.common.error.ErrorCode.VALIDATION_FAILED,
                "写操作必须提供 Idempotency-Key");
    }

    private void writeRedisSignals(Long userId, Long postId, String type, boolean inserted) {
        if (!inserted) {
            return;
        }
        try {
            redisTemplate.opsForZSet().incrementScore("feed:hot-posts", postId.toString(), "LIKE".equals(type) ? 3.0 : 5.0);
            redisTemplate.opsForHash().increment("post:" + postId + ":stats", type.toLowerCase(), 1);
            redisTemplate.opsForSet().add("user:" + userId + ":interest-tags", type.toLowerCase());
            redisTemplate.expire("user:" + userId + ":interest-tags", Duration.ofDays(30));
        } catch (RuntimeException ignored) {
            // Redis is an optimization path; MySQL idempotency remains the source of truth.
        }
    }
}
