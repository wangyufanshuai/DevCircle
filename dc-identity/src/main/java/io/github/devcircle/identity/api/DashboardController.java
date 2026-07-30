package io.github.devcircle.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.devcircle.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
class DashboardController {
    private final WebClient.Builder webClientBuilder;
    private final String userUri;
    private final String contentUri;
    private final String interactionUri;
    private final String notificationUri;

    DashboardController(WebClient.Builder webClientBuilder,
                        @Value("${devcircle.internal.user-uri:http://localhost:8102}") String userUri,
                        @Value("${devcircle.internal.content-uri:http://localhost:8103}") String contentUri,
                        @Value("${devcircle.internal.interaction-uri:http://localhost:8105}") String interactionUri,
                        @Value("${devcircle.internal.notification-uri:http://localhost:8108}") String notificationUri) {
        this.webClientBuilder = webClientBuilder;
        this.userUri = userUri;
        this.contentUri = contentUri;
        this.interactionUri = interactionUri;
        this.notificationUri = notificationUri;
    }

    @GetMapping("/dashboard")
    Mono<ApiResponse<Map<String, JsonNode>>> dashboard(@RequestHeader("X-User-Id") Long userId) {
        Mono<JsonNode> profile = get(userUri, "/api/users/" + userId, userId);
        Mono<JsonNode> progress = get(contentUri, "/api/me/reading-progress", userId);
        Mono<JsonNode> submissions = get(contentUri, "/api/me/submissions", userId);
        Mono<JsonNode> favorites = get(interactionUri, "/api/me/favorites", userId)
                .flatMap(ids -> {
                    String joined = java.util.stream.StreamSupport.stream(ids.spliterator(), false)
                            .map(JsonNode::asText).filter(value -> !value.isBlank()).limit(30)
                            .collect(java.util.stream.Collectors.joining(","));
                    return joined.isBlank()
                            ? Mono.just(JsonNodeFactory.instance.arrayNode())
                            : get(contentUri, "/api/posts/summaries?ids=" + joined, userId);
                });
        Mono<JsonNode> unread = get(notificationUri, "/api/notifications/unread-count", userId);
        Mono<JsonNode> deductionStats = get(contentUri, "/api/deduction/me/stats", userId);
        return Mono.zip(profile, progress, submissions, favorites, unread, deductionStats)
                .map(values -> {
                    Map<String, JsonNode> data = new LinkedHashMap<>();
                    data.put("profile", values.getT1());
                    data.put("readingProgress", values.getT2());
                    data.put("submissions", values.getT3());
                    data.put("favorites", values.getT4());
                    data.put("unreadNotifications", values.getT5());
                    data.put("deductionStats", values.getT6());
                    return ApiResponse.ok(data);
                });
    }

    private Mono<JsonNode> get(String baseUri, String path, Long userId) {
        return webClientBuilder.baseUrl(baseUri).build().get().uri(path)
                .header("X-User-Id", userId.toString())
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(2))
                .flatMap(body -> "OK".equals(body.path("code").asText())
                        ? Mono.just(body.path("data"))
                        : Mono.error(new IllegalStateException("required dashboard source failed")));
    }
}
