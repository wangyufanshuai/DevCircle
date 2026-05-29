package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.AuthTokenRepository;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.api.ApiResponse;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AuthTokenRepository tokenRepository;

    AuthController(JwtService jwtService,
                   @Value("${devcircle.auth.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this(jwtService, publicBaseUrl, null);
    }

    @Autowired
    AuthController(JwtService jwtService,
                   @Value("${devcircle.auth.public-base-url:http://localhost:8080}") String publicBaseUrl,
                   AuthTokenRepository tokenRepository) {
        this.jwtService = jwtService;
        this.publicBaseUrl = publicBaseUrl;
        this.tokenRepository = tokenRepository;
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                     @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        var roles = "admin".equalsIgnoreCase(request.username()) ? List.of("USER", "ADMIN") : List.of("USER");
        Long userId = "admin".equalsIgnoreCase(request.username()) ? 1L : 2L;
        LoginResponse response = tokens(userId, request.username(), roles);
        audit(userId, "LOGIN", "USER", String.valueOf(userId), traceId, Map.of("username", request.username()));
        return ApiResponse.ok(response);
    }

    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return login(request, null);
    }

    @PostMapping("/refresh")
    ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                       @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        if (tokenRepository != null && !tokenRepository.refreshTokenActive(request.refreshToken())) {
            throw new IllegalArgumentException("Refresh token is revoked or expired");
        }
        Claims claims = jwtService.parse(request.refreshToken());
        Long userId = Long.valueOf(claims.getSubject());
        String roles = claims.get("roles", String.class);
        LoginResponse response = tokens(userId, claims.get("username", String.class), Arrays.asList(roles.split(",")));
        audit(userId, "REFRESH_TOKEN", "USER", String.valueOf(userId), traceId, Map.of());
        return ApiResponse.ok(response);
    }

    ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return refresh(request, null);
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request,
                             @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        Claims claims = jwtService.parse(request.refreshToken());
        if (tokenRepository != null) {
            tokenRepository.revokeRefreshToken(request.refreshToken());
        }
        audit(Long.valueOf(claims.getSubject()), "LOGOUT", "USER", claims.getSubject(), traceId, Map.of());
        return ApiResponse.ok();
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
        Long userId = provider.equals("github") ? 101L : 102L;
        LoginResponse response = tokens(userId, username, List.of("USER"));
        audit(userId, "OAUTH2_LOGIN", "OAUTH2_PROVIDER", provider, null, Map.of("state", state, "mockProfile", code == null || code.isBlank()));
        return ApiResponse.ok(response);
    }

    private LoginResponse tokens(Long userId, String username, List<String> roles) {
        Instant accessExpiresAt = Instant.now().plusSeconds(3600);
        Instant refreshExpiresAt = Instant.now().plusSeconds(604800);
        String accessToken = jwtService.issue(userId, username, roles, accessExpiresAt);
        String refreshToken = jwtService.issue(userId, username, roles, refreshExpiresAt);
        if (tokenRepository != null) {
            tokenRepository.saveRefreshToken(userId, refreshToken, refreshExpiresAt);
        }
        return new LoginResponse(
                accessToken,
                refreshToken,
                username,
                roles,
                accessExpiresAt
        );
    }

    private void audit(Long userId, String action, String targetType, String targetId, String traceId, Map<String, Object> metadata) {
        if (tokenRepository != null) {
            tokenRepository.audit(userId, action, targetType, targetId, traceId, metadata);
        }
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
