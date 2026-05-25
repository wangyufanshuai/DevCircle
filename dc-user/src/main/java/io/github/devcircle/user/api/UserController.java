package io.github.devcircle.user.api;

import io.github.devcircle.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
class UserController {
    @GetMapping("/{id}")
    ApiResponse<UserProfile> profile(@PathVariable Long id) {
        return ApiResponse.ok(new UserProfile(id, "lin", "后端工程师，关注微服务、搜索和可靠消息。", 128, 42, "ACTIVE"));
    }

    @PostMapping("/{id}/follow")
    ApiResponse<FollowResult> follow(@PathVariable Long id) {
        return ApiResponse.ok(new FollowResult(id, true));
    }

    record UserProfile(Long id, String username, String bio, long followers, long following, String status) {
    }

    record FollowResult(Long targetUserId, boolean followed) {
    }
}

