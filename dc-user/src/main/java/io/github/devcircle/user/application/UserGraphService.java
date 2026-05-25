package io.github.devcircle.user.application;

import io.github.devcircle.user.api.FollowResult;
import io.github.devcircle.user.api.UserRecommendation;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserGraphService {
    private final UserGraphRepository repository;
    private final StringRedisTemplate redisTemplate;

    public UserGraphService(UserGraphRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    public FollowResult follow(Long userId, Long targetUserId) {
        boolean inserted = repository.follow(userId, targetUserId);
        cacheFollow(userId, targetUserId);
        return new FollowResult(targetUserId, true, !inserted);
    }

    public FollowResult unfollow(Long userId, Long targetUserId) {
        repository.unfollow(userId, targetUserId);
        try {
            redisTemplate.opsForSet().remove("user:" + userId + ":followees", targetUserId.toString());
        } catch (RuntimeException ignored) {
        }
        return new FollowResult(targetUserId, false, false);
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
        try {
            redisTemplate.opsForSet().add("user:" + userId + ":followees", targetUserId.toString());
            redisTemplate.opsForHash().put("user:" + userId + ":graph-features", "followee:" + targetUserId, "1");
        } catch (RuntimeException ignored) {
        }
    }
}
