package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.api.ApiResponse;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final JwtService jwtService;
    private final String publicBaseUrl;

    AuthController(JwtService jwtService,
                   @Value("${devcircle.auth.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.jwtService = jwtService;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var roles = "admin".equalsIgnoreCase(request.username()) ? List.of("USER", "ADMIN") : List.of("USER");
        return ApiResponse.ok(tokens(2L, request.username(), roles));
    }

    @PostMapping("/refresh")
    ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        Claims claims = jwtService.parse(request.refreshToken());
        return ApiResponse.ok(tokens(Long.valueOf(claims.getSubject()), claims.get("username", String.class), List.of("USER")));
    }

    @GetMapping("/me")
    ApiResponse<UserSession> me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        Claims claims = jwtService.parse(authorization.replace("Bearer ", ""));
        String roles = claims.get("roles", String.class);
        return ApiResponse.ok(new UserSession(Long.valueOf(claims.getSubject()), claims.get("username", String.class), Arrays.asList(roles.split(","))));
    }

    @GetMapping("/oauth2/providers")
    ApiResponse<List<String>> providers() {
        return ApiResponse.ok(List.of("github", "gitee"));
    }

    @GetMapping("/oauth2/authorize/{provider}")
    ApiResponse<OAuthAuthorizeResponse> authorize(@PathVariable String provider,
                                                  @RequestParam(defaultValue = "devcircle-demo-state") String state) {
        validateProvider(provider);
        String redirectUri = publicBaseUrl + "/api/auth/oauth2/callback/" + provider;
        String authorizationUrl = UriComponentsBuilder.fromUriString(providerAuthorizationEndpoint(provider))
                .queryParam("client_id", "${" + provider.toUpperCase() + "_CLIENT_ID}")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .queryParam("scope", "user:email")
                .build()
                .toUriString();
        return ApiResponse.ok(new OAuthAuthorizeResponse(provider, authorizationUrl, state));
    }

    @GetMapping("/oauth2/callback/{provider}")
    ApiResponse<LoginResponse> callback(@PathVariable String provider,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(defaultValue = "devcircle-demo-state") String state) {
        validateProvider(provider);
        String username = provider + "-user";
        return ApiResponse.ok(tokens(provider.equals("github") ? 101L : 102L, username, List.of("USER")));
    }

    private LoginResponse tokens(Long userId, String username, List<String> roles) {
        Instant accessExpiresAt = Instant.now().plusSeconds(3600);
        Instant refreshExpiresAt = Instant.now().plusSeconds(604800);
        return new LoginResponse(
                jwtService.issue(userId, username, roles, accessExpiresAt),
                jwtService.issue(userId, username, roles, refreshExpiresAt),
                username,
                roles,
                accessExpiresAt
        );
    }

    private void validateProvider(String provider) {
        if (!Map.of("github", true, "gitee", true).containsKey(provider)) {
            throw new IllegalArgumentException("Unsupported OAuth2 provider: " + provider);
        }
    }

    private String providerAuthorizationEndpoint(String provider) {
        return "github".equals(provider) ? "https://github.com/login/oauth/authorize" : "https://gitee.com/oauth/authorize";
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record LoginResponse(String accessToken, String refreshToken, String username, List<String> roles, Instant expiresAt) {
    }

    record UserSession(Long userId, String username, List<String> roles) {
    }

    record OAuthAuthorizeResponse(String provider, String authorizationUrl, String state) {
    }
}
