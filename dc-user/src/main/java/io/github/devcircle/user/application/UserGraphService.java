package io.github.devcircle.user.application;

import io.github.devcircle.user.api.FollowResult;
import io.github.devcircle.user.api.UserRecommendation;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserGraphService {
    private final UserGraphRepository repository;

    @Autowired
    public UserGraphService(UserGraphRepository repository) {
        this.repository = repository;
    }

    /** Compatibility constructor for isolated tests and older embedders. */
    public UserGraphService(UserGraphRepository repository, StringRedisTemplate ignoredRedisTemplate) {
        this(repository);
    }

    @Transactional
    public FollowResult follow(Long userId, Long targetUserId, String idempotencyKey) {
        validate(userId, targetUserId, idempotencyKey);
        UserGraphRepository.UserProfile actor = repository.requireProfile(userId);
        UserGraphRepository.UserProfile receiver = repository.requireProfile(targetUserId);
        if (!repository.claimOperation(userId, targetUserId, "FOLLOW", idempotencyKey)) {
            return new FollowResult(targetUserId, repository.isFollowing(userId, targetUserId), true);
        }
        boolean inserted = repository.follow(userId, targetUserId);
        if (inserted) repository.writeFollowChanged(userId, actor, targetUserId, receiver, true);
        return new FollowResult(targetUserId, true, !inserted);
    }

    @Transactional
    public FollowResult unfollow(Long userId, Long targetUserId, String idempotencyKey) {
        validate(userId, targetUserId, idempotencyKey);
        UserGraphRepository.UserProfile actor = repository.requireProfile(userId);
        UserGraphRepository.UserProfile receiver = repository.requireProfile(targetUserId);
        if (!repository.claimOperation(userId, targetUserId, "UNFOLLOW", idempotencyKey)) {
            return new FollowResult(targetUserId, repository.isFollowing(userId, targetUserId), true);
        }
        boolean removed = repository.unfollow(userId, targetUserId);
        if (removed) repository.writeFollowChanged(userId, actor, targetUserId, receiver, false);
        return new FollowResult(targetUserId, false, !removed);
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

    public boolean isFollowing(Long userId, Long targetUserId) {
        return repository.isFollowing(userId, targetUserId);
    }

    private void validate(Long userId, Long targetUserId, String idempotencyKey) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "用户 ID 无效");
        }
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能关注自己");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }
}
