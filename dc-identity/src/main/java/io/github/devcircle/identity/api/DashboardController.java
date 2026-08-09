package io.github.devcircle.identity.api;

import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/me")
class DashboardController {
    private final RestClient userClient;
    private final RestClient contentClient;
    private final RestClient interactionClient;
    private final RestClient notificationClient;
    private final Executor dashboardExecutor;

    DashboardController(RestClient.Builder restClientBuilder,
                        @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
                        @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
                        @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
                        @Value("${devcircle.internal.notification-uri:http://localhost:8108}") String notificationUri,
                        @Qualifier("dashboardExecutor") Executor dashboardExecutor) {
        this.userClient = restClientBuilder.clone().baseUrl(userUri).build();
        this.contentClient = restClientBuilder.clone().baseUrl(contentUri).build();
        this.interactionClient = restClientBuilder.clone().baseUrl(interactionUri).build();
        this.notificationClient = restClientBuilder.clone().baseUrl(notificationUri).build();
        this.dashboardExecutor = dashboardExecutor;
    }

    @GetMapping("/dashboard")
    ApiResponse<Map<String, JsonNode>> dashboard(
            Authentication authentication,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Long userId = authenticatedUserId(authentication);
        CompletableFuture<JsonNode> profile = asyncGet(userClient, "/api/users/" + userId, userId, traceId);
        CompletableFuture<JsonNode> progress = asyncGet(contentClient, "/api/me/reading-progress", userId, traceId);
        CompletableFuture<JsonNode> submissions = asyncGet(contentClient, "/api/me/submissions", userId, traceId);
        CompletableFuture<JsonNode> favoriteIds = asyncGet(interactionClient, "/api/me/favorites", userId, traceId);
        CompletableFuture<JsonNode> favorites = favoriteIds.thenApplyAsync(
                ids -> favoriteSummaries(ids, userId, traceId),
                dashboardExecutor);
        CompletableFuture<JsonNode> unread = asyncGet(
                notificationClient, "/api/notifications/unread-count", userId, traceId);
        CompletableFuture<JsonNode> deductionStats = asyncGet(
                contentClient, "/api/deduction/me/stats", userId, traceId);

        Map<String, JsonNode> data = new LinkedHashMap<>();
        data.put("profile", join(profile));
        data.put("readingProgress", join(progress));
        data.put("submissions", join(submissions));
        data.put("favorites", join(favorites));
        data.put("unreadNotifications", join(unread));
        data.put("deductionStats", join(deductionStats));
        return ApiResponse.ok(data);
    }

    private CompletableFuture<JsonNode> asyncGet(RestClient client, String path, Long userId, String traceId) {
        return CompletableFuture.supplyAsync(() -> get(client, path, userId, traceId), dashboardExecutor);
    }

    private JsonNode favoriteSummaries(JsonNode favoriteIds, Long userId, String traceId) {
        String joined = java.util.stream.StreamSupport.stream(favoriteIds.spliterator(), false)
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank())
                .limit(30)
                .collect(java.util.stream.Collectors.joining(","));
        return joined.isBlank()
                ? JsonNodeFactory.instance.arrayNode()
                : get(contentClient, "/api/posts/summaries?ids=" + joined, userId, traceId);
    }

    private JsonNode join(CompletableFuture<JsonNode> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof BusinessException businessException) throw businessException;
            throw dependency("个人中心暂时无法加载，请稍后重试", error.getCause());
        }
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

    private JsonNode get(RestClient client, String path, Long userId, String traceId) {
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri(path)
                    .header("X-User-Id", userId.toString());
            if (traceId != null && !traceId.isBlank()) {
                request.header("X-Trace-Id", traceId);
            }
            JsonNode body = request.retrieve().body(JsonNode.class);
            if (body != null && "OK".equals(body.path("code").asText())
                    && !body.path("data").isMissingNode() && !body.path("data").isNull()) {
                return body.path("data");
            }
            throw dependency("个人中心暂时无法加载，请稍后重试");
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw dependency("个人中心暂时无法加载，请稍后重试", error);
        }
    }

    private BusinessException dependency(String message) {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }

    private BusinessException dependency(String message, Throwable cause) {
        BusinessException failure = dependency(message);
        failure.initCause(cause);
        return failure;
    }
}
