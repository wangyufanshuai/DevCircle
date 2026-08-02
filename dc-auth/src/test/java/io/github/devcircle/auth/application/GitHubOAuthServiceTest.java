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

        GitHubOAuthService missingSecret = new GitHubOAuthService(
                "client-id", " ", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), RestClient.builder());
        assertCode(missingSecret::start, ErrorCode.OAUTH_NOT_CONFIGURED);

        GitHubOAuthService nullClient = new GitHubOAuthService(
                null, "client-secret", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), RestClient.builder());
        assertCode(nullClient::start, ErrorCode.OAUTH_NOT_CONFIGURED);
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

    @Test
    void callbackRejectsMissingCodeOrStateBeforeConsumingTheOneTimeState() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), RestClient.builder());

        assertCode(() -> service.callback(null, "state"), ErrorCode.OAUTH_STATE_INVALID);
        assertCode(() -> service.callback(" ", "state"), ErrorCode.OAUTH_STATE_INVALID);
        assertCode(() -> service.callback("code", null), ErrorCode.OAUTH_STATE_INVALID);
        assertCode(() -> service.callback("code", " "), ErrorCode.OAUTH_STATE_INVALID);
        verifyNoInteractions(repository);
    }

    @Test
    void providerClientErrorIsAnAuthenticationFailureWithoutLeakingItsBody() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-invalid-code")).thenReturn("verifier-invalid-code");
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository,
                mock(PasswordEncoder.class), builder);
        server.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("bad_verification_code internal-provider-node-7")
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> service.callback("expired-code", "state-invalid-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("GitHub 登录授权失败");
                    assertThat(exception.getMessage()).doesNotContain("internal-provider-node-7");
                    assertThat(exception.getCause()).isNotNull();
                });
        verify(repository, never()).bindOrCreateOAuthAccount(
                anyString(), anyString(), anyString(), anyString(), anyString());
        server.verify();
    }

    @Test
    void malformedEmailEntriesAreIgnoredAndFallbackIdentityIsNormalized() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-fallback")).thenReturn("verifier-fallback");
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        AuthTokenRepository.Account account = new AuthTokenRepository.Account(
                74L, "secondary@example.com", "orbit-login", "unused", "ACTIVE", List.of("USER"));
        when(repository.bindOrCreateOAuthAccount("github", "9003", "secondary@example.com",
                "orbit-login", "unusable-hash")).thenReturn(account);
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository, passwordEncoder, builder);

        expectGitHubExchange(server,
                "{\"id\":9003,\"login\":\" orbit-login \",\"name\":\"   \",\"email\":null}",
                "[null,{\"email\":\"   \",\"primary\":true,\"verified\":true},"
                        + "{\"email\":\" SECONDARY@EXAMPLE.COM \",\"primary\":false,\"verified\":true}]");

        assertThat(service.callback("code-fallback", "state-fallback")).isEqualTo(account);
        verify(repository).bindOrCreateOAuthAccount("github", "9003", "secondary@example.com",
                "orbit-login", "unusable-hash");
        server.verify();
    }

    @Test
    void invalidTokenOrProfilePayloadIsRejectedAsAuthenticationFailure() {
        AuthTokenRepository tokenRepository = mock(AuthTokenRepository.class);
        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
        when(tokenRepository.consumeOAuthState("state-token")).thenReturn("verifier-token");
        GitHubOAuthService tokenService = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", tokenRepository,
                mock(PasswordEncoder.class), tokenBuilder);
        tokenServer.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertCode(() -> tokenService.callback("code-token", "state-token"), ErrorCode.AUTHENTICATION_FAILED);
        tokenServer.verify();

        AuthTokenRepository profileRepository = mock(AuthTokenRepository.class);
        RestClient.Builder profileBuilder = RestClient.builder();
        MockRestServiceServer profileServer = MockRestServiceServer.bindTo(profileBuilder).build();
        when(profileRepository.consumeOAuthState("state-profile")).thenReturn("verifier-profile");
        GitHubOAuthService profileService = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", profileRepository,
                mock(PasswordEncoder.class), profileBuilder);
        profileServer.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("{\"access_token\":\"github-token\"}", MediaType.APPLICATION_JSON));
        profileServer.expect(requestTo("https://api.github.com/user"))
                .andRespond(withSuccess("{\"id\":0,\"login\":\"invalid\"}", MediaType.APPLICATION_JSON));
        assertCode(() -> profileService.callback("code-profile", "state-profile"), ErrorCode.AUTHENTICATION_FAILED);
        profileServer.verify();
    }

    @Test
    void nullBodiesAndBlankIdentityFieldsAreRejectedWithoutCreatingAnAccount() {
        AuthTokenRepository tokenRepository = mock(AuthTokenRepository.class);
        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
        when(tokenRepository.consumeOAuthState("state-null-token")).thenReturn("verifier-null-token");
        GitHubOAuthService tokenService = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", tokenRepository,
                mock(PasswordEncoder.class), tokenBuilder);
        tokenServer.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertCode(() -> tokenService.callback("code-null-token", "state-null-token"),
                ErrorCode.AUTHENTICATION_FAILED);
        tokenServer.verify();

        AuthTokenRepository profileRepository = mock(AuthTokenRepository.class);
        RestClient.Builder profileBuilder = RestClient.builder();
        MockRestServiceServer profileServer = MockRestServiceServer.bindTo(profileBuilder).build();
        when(profileRepository.consumeOAuthState("state-null-profile")).thenReturn("verifier-null-profile");
        GitHubOAuthService profileService = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", profileRepository,
                mock(PasswordEncoder.class), profileBuilder);
        profileServer.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("{\"access_token\":\"github-token\"}", MediaType.APPLICATION_JSON));
        profileServer.expect(requestTo("https://api.github.com/user"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertCode(() -> profileService.callback("code-null-profile", "state-null-profile"),
                ErrorCode.AUTHENTICATION_FAILED);
        profileServer.verify();

        AuthTokenRepository nameRepository = mock(AuthTokenRepository.class);
        RestClient.Builder nameBuilder = RestClient.builder();
        MockRestServiceServer nameServer = MockRestServiceServer.bindTo(nameBuilder).build();
        when(nameRepository.consumeOAuthState("state-name")).thenReturn("verifier-name");
        GitHubOAuthService nameService = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", nameRepository,
                mock(PasswordEncoder.class), nameBuilder);
        expectGitHubExchange(nameServer,
                "{\"id\":9004,\"login\":\"   \",\"name\":null,\"email\":null}",
                "[{\"email\":null,\"primary\":true,\"verified\":true},"
                        + "{\"email\":\"reader@example.com\",\"primary\":true,\"verified\":true}]");
        assertCode(() -> nameService.callback("code-name", "state-name"), ErrorCode.AUTHENTICATION_FAILED);
        verify(nameRepository, never()).bindOrCreateOAuthAccount(
                anyString(), anyString(), anyString(), anyString(), anyString());
        nameServer.verify();
    }

    @Test
    void profileEmailCanSelectANonPrimaryVerifiedAddress() {
        AuthTokenRepository repository = mock(AuthTokenRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(repository.consumeOAuthState("state-profile-email")).thenReturn("verifier-profile-email");
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        AuthTokenRepository.Account account = new AuthTokenRepository.Account(
                75L, "match@example.com", "Profile Name", "unused", "ACTIVE", List.of("USER"));
        when(repository.bindOrCreateOAuthAccount("github", "9005", "match@example.com",
                "Profile Name", "unusable-hash")).thenReturn(account);
        GitHubOAuthService service = new GitHubOAuthService(
                "client-id", "client-secret", "https://example.com/callback", repository, passwordEncoder, builder);
        expectGitHubExchange(server,
                "{\"id\":9005,\"login\":\"profile-login\",\"name\":\" Profile Name \","
                        + "\"email\":\"match@example.com\"}",
                "[{\"email\":\"other@example.com\",\"primary\":false,\"verified\":true},"
                        + "{\"email\":\"match@example.com\",\"primary\":false,\"verified\":true}]");

        assertThat(service.callback("code-profile-email", "state-profile-email")).isEqualTo(account);
        verify(repository).bindOrCreateOAuthAccount(
                "github", "9005", "match@example.com", "Profile Name", "unusable-hash");
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

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
