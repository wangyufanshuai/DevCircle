package io.github.devcircle.interaction.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
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
        return setActive(userId, postId, "LIKE", true, idempotencyKey);
    }

    public InteractionResult favorite(Long userId, Long postId, String idempotencyKey) {
        return setActive(userId, postId, "FAVORITE", true, idempotencyKey);
    }

    public InteractionResult unlike(Long userId, Long postId, String idempotencyKey) {
        return setActive(userId, postId, "LIKE", false, idempotencyKey);
    }

    public InteractionResult unfavorite(Long userId, Long postId, String idempotencyKey) {
        return setActive(userId, postId, "FAVORITE", false, idempotencyKey);
    }

    private InteractionResult setActive(Long userId, Long postId, String type, boolean active, String idempotencyKey) {
        requireKey(idempotencyKey);
        InteractionRepository.InteractionWriteResult result;
        if ("LIKE".equals(type)) {
            result = active ? interactionRepository.insertLike(userId, postId, idempotencyKey)
                    : interactionRepository.deleteLike(userId, postId, idempotencyKey);
        } else {
            result = active ? interactionRepository.insertFavorite(userId, postId, idempotencyKey)
                    : interactionRepository.deleteFavorite(userId, postId, idempotencyKey);
        }
        updateRedisSignals(userId, postId, type, result.inserted() ? (active ? 1 : -1) : 0);
        return new InteractionResult(postId, type, active, result.duplicateRequest() || !result.inserted(), idempotencyKey);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private void updateRedisSignals(Long userId, Long postId, String type, int direction) {
        if (direction == 0) return;
        try {
            double weight = "LIKE".equals(type) ? 3.0 : 5.0;
            redisTemplate.opsForZSet().incrementScore("feed:hot-posts", postId.toString(), weight * direction);
            redisTemplate.opsForHash().increment("post:" + postId + ":stats", type.toLowerCase(), direction);
            if (direction > 0) {
                redisTemplate.opsForSet().add("user:" + userId + ":interest-tags", type.toLowerCase());
                redisTemplate.expire("user:" + userId + ":interest-tags", Duration.ofDays(30));
            }
        } catch (RuntimeException ignored) {
            // Redis is an optimization path; MySQL remains authoritative.
        }
    }
}
