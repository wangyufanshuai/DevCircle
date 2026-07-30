package io.github.devcircle.contentdeployment;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostDetailFacadeControllerTest {
    @Test
    void exposesComposedDetailWithThePublicApiEnvelope() {
        PostDetailFacade facade = mock(PostDetailFacade.class);
        var detail = new ObjectMapper().createObjectNode().put("id", 1001).put("title", "雾港钟楼失踪案");
        when(facade.load(1001L, 2L)).thenReturn(Mono.just(detail));

        WebTestClient.bindToController(new PostDetailFacadeController(facade)).build()
                .get().uri("/api/posts/1001")
                .header("X-User-Id", "2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("OK")
                .jsonPath("$.data.id").isEqualTo(1001)
                .jsonPath("$.data.title").isEqualTo("雾港钟楼失踪案");
    }
}
