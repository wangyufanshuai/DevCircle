package io.github.devcircle.auth.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import org.mockito.ArgumentCaptor;
import org.springframework.web.util.UriComponentsBuilder;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GitHubOAuthServiceTest {
    @Test
    void startPersistsOneTimeStateAndBuildsTheMatchingPkceChallenge() throws Exception {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), RestClient.builder());

        GitHubOAuthService.OAuthStart start = service.start();

        ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
        verify(repository).saveOAuthState(eq(start.state()), verifier.capture(), any(Instant.class));
        var query = UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams();
        String expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(
                        verifier.getValue().getBytes(StandardCharsets.US_ASCII)));
        assertThat(query.getFirst("state")).isEqualTo(start.state());
        assertThat(query.getFirst("code_challenge")).isEqualTo(expectedChallenge);
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(start.authorizationUrl()).contains("client_id=client-id", "redirect_uri=")
                .doesNotContain("client-secret");
    }

    @Test
    void unconfiguredProviderIsRejectedBeforePersistingState() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        GitHubOAuthService service = new GitHubOAuthService(
                "", "", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), RestClient.builder());

        assertThatThrownBy(service::start)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.OAUTH_NOT_CONFIGURED));
        verifyNoInteractions(repository);
    }

    @Test
    void callbackUsesOnlyAnEmailVerifiedByGitHub() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-1")).thenReturn("verifier-1");
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        AuthTokenRepository.Account account = new AuthTokenRepository.Account(
                73L, "verified@example.com", "Orbit Reader", "unused", "ACTIVE", List.of("USER"));
        when(repository.bindOrCreateOAuthAccount(eq("github"), eq("9001"), eq("verified@example.com"),
                eq("Orbit Reader"), eq("unusable-hash"))).thenReturn(account);
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository, passwordEncoder, builder);

        expectGitHubExchange(server,
                "{\"id\":9001,\"login\":\"orbit\",\"name\":\"Orbit Reader\",\"email\":\"public-unverified@example.com\"}",
                "[{\"email\":\"public-unverified@example.com\",\"primary\":false,\"verified\":false},"
                        + "{\"email\":\"verified@example.com\",\"primary\":true,\"verified\":true}]");

        AuthTokenRepository.Account result = service.callback("code-1", "state-1");

        assertThat(result).isEqualTo(account);
        verify(repository).bindOrCreateOAuthAccount("github", "9001", "verified@example.com",
                "Orbit Reader", "unusable-hash");
        server.verify();
    }

    @Test
    void callbackRejectsAProfileEmailThatGitHubDoesNotVerify() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-2")).thenReturn("verifier-2");
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository, passwordEncoder, builder);

        expectGitHubExchange(server,
                "{\"id\":9002,\"login\":\"unsafe\",\"name\":\"Unsafe\",\"email\":\"unverified@example.com\"}",
                "[{\"email\":\"unverified@example.com\",\"primary\":true,\"verified\":false}]");

        assertThatThrownBy(() -> service.callback("code-2", "state-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED));
        verify(repository, never()).bindOrCreateOAuthAccount(anyString(), anyString(), anyString(), anyString(), anyString());
        server.verify();
    }

    @Test
    void providerOutageReturnsASafeDependencyFailure() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-outage")).thenReturn("verifier-outage");
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository, passwordEncoder, builder);
        server.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("upstream node github-internal-3 unavailable")
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> service.callback("code-outage", "state-outage"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(exception.getMessage()).isEqualTo("GitHub 登录暂时不可用，请稍后重试");
                    assertThat(exception.getMessage()).doesNotContain("github-internal-3");
                    assertThat(exception.getCause()).isNotNull();
                });
        verify(repository, never()).bindOrCreateOAuthAccount(
                anyString(), anyString(), anyString(), anyString(), anyString());
        server.verify();
    }

    private void expectGitHubExchange(MockRestServiceServer server, String profile, String emails) {
        server.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"github-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(profile, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/emails"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(emails, MediaType.APPLICATION_JSON));
    }
}
