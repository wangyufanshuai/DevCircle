package io.github.devcircle.interaction.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.interaction.api.InteractionResult;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InteractionService {
    private final InteractionRepository interactionRepository;

    @Autowired
    public InteractionService(InteractionRepository interactionRepository) {
        this.interactionRepository = interactionRepository;
    }

    /** Compatibility constructor retained for isolated unit tests and older embedders. */
    public InteractionService(InteractionRepository interactionRepository,
                              org.springframework.data.redis.core.StringRedisTemplate ignoredRedisTemplate) {
        this(interactionRepository);
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
        return new InteractionResult(postId, type, active, result.duplicateRequest() || !result.inserted(), idempotencyKey);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

}
