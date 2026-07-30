package io.github.devcircle.contentdeployment;

import tools.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostDetailFacadeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void composesAuthorViewerAndApprovedMediaIntoOneDetail() {
        List<ClientRequest> requests = new ArrayList<>();
        PostDetailFacade facade = facade(request -> {
            requests.add(request);
            String host = request.url().getHost();
            String path = request.url().getRawPath();
            if (host.equals("content.test")) return ok("""
                    {"id":1001,"author":{"id":7,"username":"user-7"},
                     "answerKeywords":["林墨"],"solution":"secret solution","likes":17,
                     "deductionState":{"solutionUnlocked":false},
                     "media":[{"mediaId":8,"url":"","contentType":"image/webp","status":"APPROVED"}],
                     "viewerState":{"liked":false,"favorited":false,"readingProgress":42}}
                    """);
            if (host.equals("user.test") && path.endsWith("/follow-state")) {
                return ok("{\"following\":true}");
            }
            if (host.equals("user.test")) return ok("""
                    {"id":7,"username":"林墨","avatarUrl":"/avatars/7.webp","bio":"记录异常信号",\
                     "followers":18,"following":4,"status":"ACTIVE"}
                    """);
            if (host.equals("interaction.test")) return ok("{\"liked\":true,\"favorited\":true,\"likeCount\":23,\"favoriteCount\":9}");
            if (host.equals("file.test")) return ok("""
                    [{"mediaId":8,"url":"https://signed.example/evidence.webp","expiresAt":"2099-01-01T00:00:00Z"}]
                    """);
            return failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        });

        var detail = facade.load(1001L, 2L).block();

        assertThat(detail).isNotNull();
        assertThat(detail.at("/author/username").asText()).isEqualTo("林墨");
        assertThat(detail.at("/author/avatarUrl").asText()).isEqualTo("/avatars/7.webp");
        assertThat(detail.at("/author/followerCount").asLong()).isEqualTo(18);
        assertThat(detail.at("/author/followedByViewer").asBoolean()).isTrue();
        assertThat(detail.at("/viewerState/liked").asBoolean()).isTrue();
        assertThat(detail.at("/viewerState/favorited").asBoolean()).isTrue();
        assertThat(detail.at("/likes").asLong()).isEqualTo(23);
        assertThat(detail.at("/viewerState/readingProgress").asInt()).isEqualTo(42);
        assertThat(detail.has("answerKeywords")).isFalse();
        assertThat(detail.get("solution").isNull()).isTrue();
        assertThat(detail.at("/media/0/url").asText()).isEqualTo("https://signed.example/evidence.webp");
        assertThat(requests).hasSize(5).allSatisfy(request ->
                assertThat(request.headers().getFirst("X-User-Id")).isEqualTo("2"));
    }

    @Test
    void anonymousDetailSkipsPrivateStateSources() {
        List<String> hosts = new ArrayList<>();
        PostDetailFacade facade = facade(request -> {
            hosts.add(request.url().getHost());
            if (request.url().getHost().equals("content.test")) return ok("""
                    {"id":1001,"author":{"id":7},"media":[],
                     "viewerState":{"liked":false,"favorited":false,"readingProgress":0}}
                    """);
            if (request.url().getHost().equals("user.test")) return ok("""
                    {"id":7,"username":"林墨","avatarUrl":null,"bio":"记录异常信号","followers":18,"following":4}
                    """);
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR");
        });

        var detail = facade.load(1001L, 0L).block();

        assertThat(detail).isNotNull();
        assertThat(detail.at("/author/followedByViewer").asBoolean()).isFalse();
        assertThat(detail.at("/viewerState/liked").asBoolean()).isFalse();
        assertThat(hosts).containsExactlyInAnyOrder("content.test", "user.test");
    }

    @Test
    void keepsContentBaselineWhenInteractionCounterIsNotInitialized() {
        PostDetailFacade facade = facade(request -> {
            String host = request.url().getHost();
            String path = request.url().getRawPath();
            if (host.equals("content.test")) return ok("""
                    {"id":1001,"likes":17,"author":{"id":7},"media":[],
                     "viewerState":{"liked":false,"favorited":false,"readingProgress":0}}
                    """);
            if (host.equals("user.test") && path.endsWith("/follow-state")) return ok("{\"following\":false}");
            if (host.equals("user.test")) return ok("""
                    {"id":7,"username":"author-7","avatarUrl":null,"bio":null,"followers":0,"following":0}
                    """);
            if (host.equals("interaction.test")) return ok("{\"liked\":false,\"favorited\":false,\"likeCount\":null}");
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR");
        });

        var detail = facade.load(1001L, 2L).block();

        assertThat(detail).isNotNull();
        assertThat(detail.at("/likes").asLong()).isEqualTo(17);
    }

    @Test
    void unlockedSolutionRemainsAvailableAfterDeductionSuccess() {
        PostDetailFacade facade = facade(request -> {
            if (request.url().getHost().equals("content.test")) return ok("""
                    {"id":1001,"author":{"id":7},"media":[],
                     "deductionState":{"solutionUnlocked":true},"solution":"已验证的解析",
                     "viewerState":{"liked":false,"favorited":false,"readingProgress":0}}
                    """);
            if (request.url().getHost().equals("user.test")) return ok("""
                    {"id":7,"username":"林墨","avatarUrl":null,"bio":"记录异常信号","followers":18,"following":4}
                    """);
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR");
        });

        var detail = facade.load(1001L, 0L).block();

        assertThat(detail).isNotNull();
        assertThat(detail.at("/solution").asText()).isEqualTo("已验证的解析");
    }

    @Test
    void missingSignedMediaFailsInsteadOfReturningBrokenSuccess() {
        PostDetailFacade facade = facade(request -> {
            if (request.url().getHost().equals("content.test")) return ok("""
                    {"id":1001,"author":{"id":7},"media":[{"mediaId":8}],
                     "viewerState":{"liked":false,"favorited":false,"readingProgress":0}}
                    """);
            if (request.url().getHost().equals("user.test")) return ok("""
                    {"id":7,"username":"林墨","avatarUrl":null,"bio":"记录异常信号","followers":18,"following":4}
                    """);
            if (request.url().getHost().equals("file.test")) return ok("[]");
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR");
        });

        assertThatThrownBy(() -> facade.load(1001L, 0L).block())
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private PostDetailFacade facade(ExchangeFunction exchangeFunction) {
        return new PostDetailFacade(WebClient.builder().exchangeFunction(exchangeFunction), objectMapper,
                "https://content.test", "https://user.test", "https://interaction.test", "https://file.test");
    }

    private Mono<ClientResponse> ok(String data) {
        return response(HttpStatus.OK, "{\"code\":\"OK\",\"message\":\"success\",\"data\":" + data + "}");
    }

    private Mono<ClientResponse> failure(HttpStatus status, String code) {
        return response(status, "{\"code\":\"" + code + "\",\"message\":\"failed\",\"data\":null}");
    }

    private Mono<ClientResponse> response(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }
}
