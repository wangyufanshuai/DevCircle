package io.github.devcircle.identity.api;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesProfileProgressFavoritesAndStatsWithJackson3Responses() {
        DashboardController controller = controller(request -> {
            String path = request.url().getRawPath();
            if (path.equals("/api/users/2")) return ok("{\"id\":2,\"username\":\"观测者\"}");
            if (path.equals("/api/me/reading-progress")) return ok("[]");
            if (path.equals("/api/me/submissions")) return ok("[]");
            if (path.equals("/api/me/favorites")) return ok("[1001]");
            if (path.equals("/api/posts/summaries")) return ok("[{\"id\":1001,\"title\":\"雾港钟楼失踪案\"}]");
            if (path.equals("/api/notifications/unread-count")) return ok("2");
            if (path.equals("/api/deduction/me/stats")) return ok("{\"attempts\":3,\"correctCount\":2,\"revealedCount\":0,\"totalPoints\":40}");
            return failure(HttpStatus.NOT_FOUND);
        });

        var response = controller.dashboard(2L).block();

        assertThat(response).isNotNull();
        assertThat(response.data().get("profile").path("username").asText()).isEqualTo("观测者");
        assertThat(response.data().get("favorites").get(0).path("title").asText()).isEqualTo("雾港钟楼失踪案");
        assertThat(response.data().get("unreadNotifications").asInt()).isEqualTo(2);
        assertThat(response.data().get("deductionStats").path("totalPoints").asInt()).isEqualTo(40);
    }

    @Test
    void requiredSourceFailureIsReturnedAsDependencyUnavailable() {
        DashboardController controller = controller(request -> {
            if (request.url().getRawPath().equals("/api/users/2")) return failure(HttpStatus.SERVICE_UNAVAILABLE);
            return ok("[]");
        });

        assertThatThrownBy(() -> controller.dashboard(2L).block())
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private DashboardController controller(ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new DashboardController(builder, "https://user.test", "https://content.test",
                "https://interaction.test", "https://notification.test");
    }

    private Mono<ClientResponse> ok(String data) {
        return response(HttpStatus.OK, "{\"code\":\"OK\",\"message\":\"success\",\"data\":" + data + "}");
    }

    private Mono<ClientResponse> failure(HttpStatus status) {
        return response(status, "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"message\":\"failed\",\"data\":null}");
    }

    private Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
