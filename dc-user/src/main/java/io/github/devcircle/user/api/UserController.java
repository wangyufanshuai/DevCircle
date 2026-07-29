package io.github.devcircle.user.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.application.UserGraphService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
class UserController {
    private final UserGraphService userGraphService;

    UserController(UserGraphService userGraphService) {
        this.userGraphService = userGraphService;
    }

    @GetMapping("/{id}")
    ApiResponse<UserProfile> profile(@PathVariable Long id) {
        return userGraphService.profile(id)
                .map(profile -> ApiResponse.ok(new UserProfile(profile.userId(), profile.username(), profile.bio(),
                        profile.followerCount(), profile.followingCount(), "ACTIVE")))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在"));
    }

    @PostMapping("/{id}/follow")
    ApiResponse<FollowResult> follow(@RequestHeader("X-User-Id") Long userId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @PathVariable Long id) {
        return ApiResponse.ok(userGraphService.follow(userId, id, idempotencyKey));
    }

    @DeleteMapping("/{id}/follow")
    ApiResponse<FollowResult> unfollow(@RequestHeader("X-User-Id") Long userId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @PathVariable Long id) {
        return ApiResponse.ok(userGraphService.unfollow(userId, id, idempotencyKey));
    }

    @GetMapping("/{id}/followees")
    ApiResponse<List<Long>> followees(@PathVariable Long id) {
        return ApiResponse.ok(userGraphService.followees(id));
    }

    @GetMapping("/{id}/followers")
    ApiResponse<List<Long>> followers(@PathVariable Long id) {
        return ApiResponse.ok(userGraphService.followers(id));
    }

    @GetMapping("/{id}/recommendations")
    ApiResponse<List<UserRecommendation>> recommendations(@PathVariable Long id) {
        return ApiResponse.ok(userGraphService.recommendations(id));
    }

    record UserProfile(Long id, String username, String bio, long followers, long following, String status) {
    }
}
