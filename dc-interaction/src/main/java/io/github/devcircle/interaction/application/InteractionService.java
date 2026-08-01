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
    private final PostPublicationGateway postPublicationGateway;

    @Autowired
    public InteractionService(InteractionRepository interactionRepository,
                              PostPublicationGateway postPublicationGateway) {
        this.interactionRepository = interactionRepository;
        this.postPublicationGateway = postPublicationGateway;
    }

    /** Compatibility constructor retained for isolated unit tests and older embedders. */
    public InteractionService(InteractionRepository interactionRepository,
                              org.springframework.data.redis.core.StringRedisTemplate ignoredRedisTemplate) {
        this(interactionRepository, postId -> new PostPublicationGateway.PublishedPost(postId, 0));
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
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (postId == null || postId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "内容 ID 无效");
        }
        requireKey(idempotencyKey);
        PostPublicationGateway.PublishedPost publishedPost = postPublicationGateway.requirePublished(postId);
        InteractionRepository.InteractionWriteResult result;
        if ("LIKE".equals(type)) {
            result = active ? interactionRepository.insertLike(userId, postId, publishedPost.likeCount(), idempotencyKey)
                    : interactionRepository.deleteLike(userId, postId, publishedPost.likeCount(), idempotencyKey);
        } else {
            result = active ? interactionRepository.insertFavorite(userId, postId, publishedPost.likeCount(), idempotencyKey)
                    : interactionRepository.deleteFavorite(userId, postId, publishedPost.likeCount(), idempotencyKey);
        }
        return new InteractionResult(postId, type, active, result.duplicateRequest() || !result.inserted(), idempotencyKey);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

}
