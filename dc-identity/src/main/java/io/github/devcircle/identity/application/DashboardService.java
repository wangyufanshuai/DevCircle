package io.github.devcircle.identity.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.application.UserGraphService;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Aggregates the required identity dashboard sources with fail-closed semantics. */
@Service
public class DashboardService {
    private static final String UNAVAILABLE_MESSAGE = "个人中心暂时无法加载，请稍后重试";

    private final UserGraphService userGraphService;
    private final RestClient contentClient;
    private final RestClient interactionClient;
    private final RestClient notificationClient;
    private final Executor dashboardExecutor;

    public DashboardService(UserGraphService userGraphService,
                            RestClient.Builder restClientBuilder,
                            @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
                            @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
                            @Value("${devcircle.internal.notification-uri:http://localhost:8108}") String notificationUri,
                            @Qualifier("dashboardExecutor") Executor dashboardExecutor) {
        this.userGraphService = userGraphService;
        this.contentClient = restClientBuilder.clone().baseUrl(contentUri).build();
        this.interactionClient = restClientBuilder.clone().baseUrl(interactionUri).build();
        this.notificationClient = restClientBuilder.clone().baseUrl(notificationUri).build();
        this.dashboardExecutor = dashboardExecutor;
    }

    public Map<String, JsonNode> dashboard(Long userId, String traceId) {
        CompletableFuture<JsonNode> profile = CompletableFuture.supplyAsync(
                () -> localProfile(userId), dashboardExecutor);
        CompletableFuture<JsonNode> progress = asyncGet(contentClient, "/api/me/reading-progress", userId, traceId);
        CompletableFuture<JsonNode> submissions = asyncGet(contentClient, "/api/me/submissions", userId, traceId);
        CompletableFuture<JsonNode> favoriteIds = asyncGet(interactionClient, "/api/me/favorites", userId, traceId);
        CompletableFuture<JsonNode> favorites = favoriteIds.thenApplyAsync(
                ids -> favoriteSummaries(ids, userId, traceId), dashboardExecutor);
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
        return data;
    }

    private CompletableFuture<JsonNode> asyncGet(RestClient client, String path, Long userId, String traceId) {
        return CompletableFuture.supplyAsync(() -> get(client, path, userId, traceId), dashboardExecutor);
    }

    private JsonNode localProfile(Long userId) {
        UserGraphRepository.UserProfile profile = userGraphService.profile(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在"));
        return JsonNodeFactory.instance.objectNode()
                .put("id", profile.userId())
                .put("username", profile.username())
                .put("avatarUrl", profile.avatarUrl())
                .put("bio", profile.bio())
                .put("followers", profile.followerCount())
                .put("following", profile.followingCount())
                .put("status", "ACTIVE");
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
            throw dependency(error.getCause());
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
            throw dependency(null);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw dependency(error);
        }
    }

    private BusinessException dependency(Throwable cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        if (cause != null) failure.initCause(cause);
        return failure;
    }
}
