package io.github.devcircle.identity.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.identity.application.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/me")
class DashboardController {
    private final DashboardService dashboardService;

    DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    ApiResponse<Map<String, JsonNode>> dashboard(
            Authentication authentication,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(dashboardService.dashboard(authenticatedUserId(authentication), traceId));
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录后再继续");
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0) throw new NumberFormatException("non-positive user id");
            return userId;
        } catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态无效");
        }
    }
}
