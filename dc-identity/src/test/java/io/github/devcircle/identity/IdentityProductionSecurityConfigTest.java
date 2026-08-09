package io.github.devcircle.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityProductionSecurityConfigTest {
    private static final String ORIGIN = "https://mysticverse.example.com";
    private static final String FRONTEND_CALLBACK = ORIGIN + "/auth/callback";
    private static final String GITHUB_CALLBACK = ORIGIN + "/api/auth/oauth2/github/callback";

    @Test
    void productionRejectsInsecureCookiesAndLocalOrMismatchedCallbacks() {
        assertInvalid(false, ORIGIN, FRONTEND_CALLBACK, false, "", "", GITHUB_CALLBACK,
                "AUTH_COOKIE_SECURE");
        assertInvalid(true, "http://localhost:5501", FRONTEND_CALLBACK, false, "", "", GITHUB_CALLBACK,
                "PUBLIC_ORIGIN");
        assertInvalid(true, ORIGIN, "http://localhost:5501/auth/callback", false, "", "", GITHUB_CALLBACK,
                "AUTH_FRONTEND_CALLBACK_URI");
        assertInvalid(true, ORIGIN, FRONTEND_CALLBACK, false, "", "",
                "http://localhost:8080/api/auth/oauth2/github/callback", "GITHUB_CALLBACK_URI");
    }

    @Test
    void enabledProductionGitHubOAuthRequiresBothCredentials() {
        assertInvalid(true, ORIGIN, FRONTEND_CALLBACK, true, "", "", GITHUB_CALLBACK,
                "GITHUB_CLIENT_ID");
        assertInvalid(true, ORIGIN, FRONTEND_CALLBACK, true, "github-client", "", GITHUB_CALLBACK,
                "GITHUB_CLIENT_SECRET");

        IdentityProductionSecurityConfig.validateProductionConfiguration(
                true, ORIGIN, true, FRONTEND_CALLBACK, true,
                "github-client-id", "github-client-secret-with-enough-entropy", GITHUB_CALLBACK);
    }

    @Test
    void developmentDoesNotRequireSecureCookiesCallbacksOrGitHubCredentials() {
        IdentityProductionSecurityConfig.validateProductionConfiguration(
                false, "http://localhost:5501", false, "http://localhost:5501/auth/callback",
                true, "", "", "http://localhost:8080/api/auth/oauth2/github/callback");
    }

    private void assertInvalid(boolean secureCookies,
                               String origin,
                               String frontendCallback,
                               boolean githubEnabled,
                               String githubClientId,
                               String githubClientSecret,
                               String githubCallback,
                               String expectedMessage) {
        assertThatThrownBy(() -> IdentityProductionSecurityConfig.validateProductionConfiguration(
                true, origin, secureCookies, frontendCallback, githubEnabled,
                githubClientId, githubClientSecret, githubCallback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }
}
