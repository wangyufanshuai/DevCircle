package io.github.devcircle.user.api;

import io.github.devcircle.common.api.ApiResponse;
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
        return ApiResponse.ok(new UserProfile(id, "lin", "后端工程师，关注微服务、搜索和可靠消息。", 128, 42, "ACTIVE"));
    }

    @PostMapping("/{id}/follow")
    ApiResponse<FollowResult> follow(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                     @PathVariable Long id) {
        return ApiResponse.ok(userGraphService.follow(userId, id));
    }

    @DeleteMapping("/{id}/follow")
    ApiResponse<FollowResult> unfollow(@RequestHeader(value = "X-User-Id", defaultValue = "2") Long userId,
                                       @PathVariable Long id) {
        return ApiResponse.ok(userGraphService.unfollow(userId, id));
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
