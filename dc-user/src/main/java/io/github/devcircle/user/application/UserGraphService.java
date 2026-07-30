package io.github.devcircle.user.application;

import io.github.devcircle.user.api.FollowResult;
import io.github.devcircle.user.api.UserRecommendation;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserGraphService {
    private final UserGraphRepository repository;
    private final StringRedisTemplate redisTemplate;

    public UserGraphService(UserGraphRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public FollowResult follow(Long userId, Long targetUserId, String idempotencyKey) {
        validate(userId, targetUserId, idempotencyKey);
        if (!repository.claimOperation(userId, targetUserId, "FOLLOW", idempotencyKey)) {
            return new FollowResult(targetUserId, repository.isFollowing(userId, targetUserId), true);
        }
        boolean inserted = repository.follow(userId, targetUserId);
        cacheFollow(userId, targetUserId);
        return new FollowResult(targetUserId, true, !inserted);
    }

    @Transactional
    public FollowResult unfollow(Long userId, Long targetUserId, String idempotencyKey) {
        validate(userId, targetUserId, idempotencyKey);
        if (!repository.claimOperation(userId, targetUserId, "UNFOLLOW", idempotencyKey)) {
            return new FollowResult(targetUserId, repository.isFollowing(userId, targetUserId), true);
        }
        repository.unfollow(userId, targetUserId);
        redisTemplate.opsForSet().remove("user:" + userId + ":followees", targetUserId.toString());
        return new FollowResult(targetUserId, false, false);
    }

    public Optional<UserGraphRepository.UserProfile> profile(Long userId) {
        return repository.profile(userId);
    }

    public List<UserGraphRepository.UserProfile> profiles(List<Long> userIds) {
        return repository.profiles(userIds);
    }

    public List<Long> followees(Long userId) {
        return repository.followees(userId);
    }

    public List<Long> followers(Long userId) {
        return repository.followers(userId);
    }

    public List<UserRecommendation> recommendations(Long userId) {
        return repository.twoHopRecommendations(userId).stream()
                .map(item -> new UserRecommendation(item.userId(), item.mutualFollowees(), "你们有 " + item.mutualFollowees() + " 个共同关注"))
                .toList();
    }

    private void cacheFollow(Long userId, Long targetUserId) {
        redisTemplate.opsForSet().add("user:" + userId + ":followees", targetUserId.toString());
        redisTemplate.opsForHash().put("user:" + userId + ":graph-features", "followee:" + targetUserId, "1");
    }

    public boolean isFollowing(Long userId, Long targetUserId) {
        return repository.isFollowing(userId, targetUserId);
    }

    private void validate(Long userId, Long targetUserId, String idempotencyKey) {
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能关注自己");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }
}
