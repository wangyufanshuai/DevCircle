package io.github.devcircle.identity.api;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.identity.application.DashboardService;
import io.github.devcircle.user.application.UserGraphService;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

class DashboardControllerTest {
    private static final String TRACE_ID = "trace-dashboard-1";

    @Test
    void aggregatesProfileProgressFavoritesAndStatsForAuthenticatedPrincipal() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://content.test/api/me/reading-progress", """
                [{"postId":1001,"title":"Clocktower","theme":"DEDUCTION","progressPercent":42,
                  "completed":false,"available":true,"trackState":"IN_PROGRESS"}]
                """);
        expect(server, "https://content.test/api/me/submissions", "[]");
        expect(server, "https://content.test/api/posts/summaries?ids=1001",
                "[{\"id\":1001,\"title\":\"Clocktower Case\"}]");
        expect(server, "https://notification.test/api/notifications/unread-count", "2");
        expect(server, "https://content.test/api/deduction/me/stats",
                "{\"attempts\":3,\"correctCount\":2,\"revealedCount\":0,\"totalPoints\":40}");
        UserGraphService users = mock(UserGraphService.class);
        when(users.profile(2L)).thenReturn(Optional.of(new UserGraphRepository.UserProfile(
                2L, "Observer", null, "Orbit reader", 7, 3)));
        InteractionRepository interactions = mock(InteractionRepository.class);
        when(interactions.favoritePostIds(2L, 30)).thenReturn(List.of(1001L));
        DashboardController controller = controller(builder, users, interactions);

        var authentication = UsernamePasswordAuthenticationToken.authenticated("2", null, List.of());
        var response = controller.dashboard(authentication, TRACE_ID);

        assertThat(response.data().get("profile").path("username").asText()).isEqualTo("Observer");
        assertThat(response.data().get("favorites").get(0).path("title").asText()).isEqualTo("Clocktower Case");
        assertThat(response.data().get("unreadNotifications").asInt()).isEqualTo(2);
        assertThat(response.data().get("deductionStats").path("totalPoints").asInt()).isEqualTo(40);
        assertThat(response.data().get("readingProgress").get(0).path("trackState").asText())
                .isEqualTo("IN_PROGRESS");
        server.verify();
    }

    @Test
    void requiredSourceFailureIsReturnedAsDependencyUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://content.test/api/me/reading-progress", "[]");
        expect(server, "https://content.test/api/me/submissions", "[]");
        expect(server, "https://content.test/api/posts/summaries?ids=1001", "[]");
        expect(server, "https://notification.test/api/notifications/unread-count", "0");
        expect(server, "https://content.test/api/deduction/me/stats", "{}");
        UserGraphService users = mock(UserGraphService.class);
        when(users.profile(2L)).thenThrow(new IllegalStateException("user database unavailable"));
        InteractionRepository interactions = mock(InteractionRepository.class);
        when(interactions.favoritePostIds(2L, 30)).thenReturn(List.of(1001L));
        DashboardController controller = controller(builder, users, interactions);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("2", null, List.of());

        assertThatThrownBy(() -> controller.dashboard(authentication, TRACE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        server.verify();
    }

    @Test
    void rejectsAnInvalidAuthenticatedPrincipalBeforeCallingDependencies() {
        DashboardController controller = controller(RestClient.builder(), mock(UserGraphService.class),
                mock(InteractionRepository.class));
        var authentication = UsernamePasswordAuthenticationToken.authenticated("not-a-user", null, List.of());

        assertThatThrownBy(() -> controller.dashboard(authentication, TRACE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    private DashboardController controller(RestClient.Builder builder, UserGraphService users,
                                           InteractionRepository interactions) {
        DashboardService service = new DashboardService(users, builder, "https://content.test",
                "https://notification.test", interactions, Runnable::run);
        return new DashboardController(service);
    }

    private void expect(MockRestServiceServer server, String uri, String data) {
        server.expect(once(), requestTo(uri))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "2"))
                .andExpect(header("X-Trace-Id", TRACE_ID))
                .andRespond(withSuccess("{\"code\":\"OK\",\"message\":\"success\",\"data\":" + data + "}",
                        MediaType.APPLICATION_JSON));
    }
}
