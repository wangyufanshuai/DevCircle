package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.AuthService;
import io.github.devcircle.auth.application.AuthTokenRepository;
import io.github.devcircle.auth.application.GitHubOAuthService;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-production-style-validation-123456";

    @Test
    void accessTokenCarriesOnlyAccessClaims() {
        JwtService jwtService = new JwtService(SECRET);
        String token = jwtService.issueAccess(42L, "林墨", List.of("USER"), Instant.now().plusSeconds(60));

        var claims = jwtService.parseAccess(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
    }

    @Test
    void registrationPersistsAnArgon2PasswordAndRefreshSession() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        JwtService jwtService = new JwtService(SECRET);
        var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        var account = new AuthTokenRepository.Account(7L, "reader@example.com", "星海读者", "unused", "ACTIVE", List.of("USER"));
        when(repository.createAccount(anyString(), anyString(), anyString())).thenReturn(account);
        AuthService service = new AuthService(repository, jwtService, encoder);

        AuthService.SessionTokens tokens = service.register("Reader@Example.com", "long-password-2026", "星海读者", "trace");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).hasSizeGreaterThan(32);
        assertThat(tokens.csrfToken()).hasSizeGreaterThan(32);
        verify(repository).createAccount(anyString(), anyString(), anyString());
        verify(repository).saveRefreshToken(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void weakPasswordIsRejectedBeforeDatabaseWrite() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        AuthService service = new AuthService(repository, new JwtService(SECRET), Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        assertThatThrownBy(() -> service.register("reader@example.com", "short", "星海读者", "trace"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void oauthAccountConflictReturnsToTheFrontendWithoutLeakingProviderDetails() throws Exception {
        AuthService authService = mock(AuthService.class);
        GitHubOAuthService oauthService = mock(GitHubOAuthService.class);
        when(oauthService.callback("code", "state")).thenThrow(
                new BusinessException(ErrorCode.OAUTH_ACCOUNT_CONFLICT, "conflict"));
        AuthController controller = new AuthController(authService, oauthService,
                "https://mysticverse.example.com/auth/callback", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.finishGitHubOAuth("code", "state", "state", new MockHttpServletRequest(), response);

        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://mysticverse.example.com/auth/callback?error=OAUTH_ACCOUNT_CONFLICT");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
            assertThat(cookie).contains("dc_oauth_state=");
            assertThat(cookie).contains("Max-Age=0");
            assertThat(cookie).contains("Secure");
        });
    }

    @Test
    void secureSessionCookiesUseSeparateRefreshAndCsrfScopes() {
        AuthService authService = mock(AuthService.class);
        GitHubOAuthService oauthService = mock(GitHubOAuthService.class);
        Instant accessExpires = Instant.now().plusSeconds(900);
        Instant refreshExpires = Instant.now().plusSeconds(3600);
        var user = new AuthService.UserSession(7L, "星海读者", List.of("USER"));
        when(authService.login("reader@example.com", "correct-password1", "login-key", "trace-cookie"))
                .thenReturn(new AuthService.SessionTokens(
                        "access-token", accessExpires, "refresh-token", "csrf-token", refreshExpires, user));
        AuthController controller = new AuthController(authService, oauthService,
                "https://mysticverse.example.com/auth/callback", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-cookie");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(new AuthController.LoginRequest("reader@example.com", "correct-password1"),
                "login-key", request, response);

        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("dc_refresh=refresh-token", "Path=/api/auth", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
            assertThat(cookie).contains("dc_csrf=csrf-token", "Path=/", "Secure", "SameSite=Lax");
            assertThat(cookie).doesNotContain("HttpOnly");
        });
    }

    @Test
    void logoutWithoutARefreshSessionIdempotentlyClearsStaleCookies() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService, mock(GitHubOAuthService.class),
                "https://mysticverse.example.com/auth/callback", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(null, null, null, "logout-key",
                new MockHttpServletRequest(), response);

        verifyNoInteractions(authService);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allSatisfy(cookie ->
                assertThat(cookie).contains("Max-Age=0", "Secure", "SameSite=Lax"));
    }

    @Test
    void failedServerSideLogoutDoesNotPretendTheBrowserSessionWasCleared() {
        AuthService authService = mock(AuthService.class);
        BusinessException outage = new BusinessException(
                ErrorCode.DEPENDENCY_UNAVAILABLE, "认证服务暂时不可用");
        org.mockito.Mockito.doThrow(outage).when(authService)
                .logout("refresh", "csrf", "logout-key", "trace-logout");
        AuthController controller = new AuthController(authService, mock(GitHubOAuthService.class),
                "https://mysticverse.example.com/auth/callback", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-logout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.logout(
                "refresh", "csrf", "csrf", "logout-key", request, response)).isSameAs(outage);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }
}
