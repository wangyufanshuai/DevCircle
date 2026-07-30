package io.github.devcircle.auth.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

@Service
public class GitHubOAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String clientId;
    private final String clientSecret;
    private final String callbackUri;
    private final AuthTokenRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final RestClient restClient;

    public GitHubOAuthService(@Value("${devcircle.auth.github.client-id:}") String clientId,
                              @Value("${devcircle.auth.github.client-secret:}") String clientSecret,
                              @Value("${devcircle.auth.github.callback-uri:http://localhost:8080/api/auth/oauth2/github/callback}") String callbackUri,
                              AuthTokenRepository repository,
                              PasswordEncoder passwordEncoder,
                              RestClient.Builder restClientBuilder) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callbackUri = callbackUri;
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.restClient = restClientBuilder.build();
    }

    public OAuthStart start() {
        requireConfigured();
        String state = randomToken(24);
        String verifier = randomToken(48);
        String challenge = sha256Base64(verifier);
        repository.saveOAuthState(state, verifier, Instant.now().plus(10, ChronoUnit.MINUTES));
        String authorizationUrl = UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", callbackUri)
                .queryParam("scope", "read:user user:email")
                .queryParam("state", state)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .toUriString();
        return new OAuthStart(state, authorizationUrl);
    }

    public AuthTokenRepository.Account callback(String code, String state) {
        requireConfigured();
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_STATE_INVALID, "GitHub 登录回调缺少必要参数");
        }
        String verifier = repository.consumeOAuthState(state);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", callbackUri);
        form.add("code_verifier", verifier);
        TokenResponse token = restClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "GitHub 登录授权失败");
        }
        GitHubUser profile = restClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + token.accessToken())
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(GitHubUser.class);
        if (profile == null || profile.id() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "无法读取 GitHub 用户资料");
        }
        GitHubEmail[] emails = restClient.get()
                .uri("https://api.github.com/user/emails")
                .header("Authorization", "Bearer " + token.accessToken())
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(GitHubEmail[].class);
        String profileEmail = profile.email();
        String email = emails == null ? null : List.of(emails).stream()
                .filter(GitHubEmail::verified)
                .filter(item -> item.primary()
                        || (profileEmail != null && item.email().equalsIgnoreCase(profileEmail)))
                .sorted((left, right) -> Boolean.compare(right.primary(), left.primary()))
                .map(GitHubEmail::email)
                .findFirst()
                .orElseGet(() -> List.of(emails).stream()
                        .filter(GitHubEmail::verified)
                        .map(GitHubEmail::email)
                        .findFirst()
                        .orElse(null));
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "GitHub 账号没有可用的已验证邮箱");
        }
        String displayName = profile.name() == null || profile.name().isBlank() ? profile.login() : profile.name();
        String unusablePassword = passwordEncoder.encode(randomToken(48));
        return repository.bindOrCreateOAuthAccount("github", String.valueOf(profile.id()), email.toLowerCase(), displayName, unusablePassword);
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED, "GitHub 登录尚未配置");
        }
    }

    private String randomToken(int bytesLength) {
        byte[] bytes = new byte[bytesLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Base64(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public record OAuthStart(String state, String authorizationUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubUser(Long id, String login, String name, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubEmail(String email, boolean primary, boolean verified) {
    }
}
