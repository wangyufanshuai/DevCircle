package io.github.devcircle.contentdeployment;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.PostDetailView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ContentPostDetailComposerTest {
    @Test
    void composesRealAuthorFollowAndInteractionStateInParallel() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://user.test/api/users/7"))
                .andExpect(header("X-User-Id", "2"))
                .andExpect(header("X-Trace-Id", "trace-7"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-access"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"id":7,"username":"林墨","avatarUrl":"/avatar/7.webp","bio":"记录异常信号","followers":18}}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://interaction.test/api/posts/1001/interaction-state"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-access"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"liked":true,"favorited":true,"likeCount":521}}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo("https://user.test/api/users/7/follow-state"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer delegated-access"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"following":true}}
                        """, MediaType.APPLICATION_JSON));

        PostDetailView result = fixture.composer.compose(detail(), 2L, "trace-7", "Bearer delegated-access");

        assertThat(result.author().username()).isEqualTo("林墨");
        assertThat(result.author().followerCount()).isEqualTo(18);
        assertThat(result.author().followedByViewer()).isTrue();
        assertThat(result.viewerState().liked()).isTrue();
        assertThat(result.viewerState().favorited()).isTrue();
        assertThat(result.viewerState().readingProgress()).isEqualTo(42);
        assertThat(result.likes()).isEqualTo(521);
        fixture.server.verify();
    }

    @Test
    void failsExplicitlyWhenRequiredAuthorDataIsUnavailable() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://user.test/api/users/7")).andRespond(withServerError());

        assertThatThrownBy(() -> fixture.composer.compose(detail(), null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        fixture.server.verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient userClient = builder.clone().baseUrl("https://user.test").build();
        RestClient interactionClient = builder.clone().baseUrl("https://interaction.test").build();
        return new Fixture(server, new ContentPostDetailComposer(userClient, interactionClient, Runnable::run));
    }

    private PostDetailView detail() {
        return new PostDetailView(
                1001L, "TEXT", "DEDUCTION", "CASE_FILE", "HARD", true, 58,
                "雾港钟楼失踪案", "钟声停在 00:07", "# 雾港钟楼",
                new PostDetailView.AuthorView(7L, "user-7", null, null, null, null),
                List.of("推理"), List.of(), 520, 4, 12800, "PUBLISHED", Instant.parse("2026-08-01T00:00:00Z"),
                List.of(), List.of(), List.of(), "谁制造了不在场证明？", List.of(), List.of(), List.of(),
                new PostDetailView.ViewerState(false, false, 42, "reading-section-2", false),
                new PostDetailView.DeductionState(false, false, false, 0, false), null);
    }

    private record Fixture(MockRestServiceServer server, ContentPostDetailComposer composer) {
    }
}
