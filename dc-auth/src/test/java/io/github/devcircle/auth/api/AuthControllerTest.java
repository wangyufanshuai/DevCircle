package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.JwtService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {
    private final JwtService jwtService = new JwtService("devcircle-local-demo-secret-devcircle-local-demo-secret");
    private final AuthController controller = new AuthController(jwtService, "http://localhost:8080");

    @Test
    void passwordLoginIssuesJwtAndMeParsesRoles() {
        var login = controller.login(new AuthController.LoginRequest("admin", "devcircle")).data();

        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.roles()).contains("ADMIN", "USER");

        var session = controller.me("Bearer " + login.accessToken()).data();
        assertThat(session.username()).isEqualTo("admin");
        assertThat(session.roles()).contains("ADMIN", "USER");
    }

    @Test
    void mockOAuthCallbackIssuesUserTokenAndRefreshWorks() {
        var callback = controller.callback("github", "demo-code", "state").data();
        var refreshed = controller.refresh(new AuthController.RefreshRequest(callback.refreshToken())).data();

        assertThat(callback.username()).isEqualTo("github-user");
        assertThat(refreshed.accessToken()).isNotBlank();
    }

    @Test
    void authorizeBuildsProviderUrl() {
        var response = controller.authorize("gitee", "abc").data();

        assertThat(response.provider()).isEqualTo("gitee");
        assertThat(response.authorizationUrl()).contains("gitee.com/oauth/authorize", "state=abc");
    }
}
