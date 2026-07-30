package io.github.devcircle.auth.api;

import io.github.devcircle.auth.application.AuthService;
import io.github.devcircle.auth.application.GitHubOAuthService;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.BusinessException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    static final String REFRESH_COOKIE = "dc_refresh";
    static final String CSRF_COOKIE = "dc_csrf";
    private final AuthService authService;
    private final GitHubOAuthService gitHubOAuthService;
    private final boolean secureCookies;
    private final String frontendCallbackUri;

    AuthController(AuthService authService,
                   GitHubOAuthService gitHubOAuthService,
                   @Value("${devcircle.auth.frontend-callback-uri:http://localhost:5501/auth/callback}") String frontendCallbackUri,
                   @Value("${devcircle.auth.cookie-secure:false}") boolean secureCookies) {
        this.authService = authService;
        this.gitHubOAuthService = gitHubOAuthService;
        this.frontendCallbackUri = frontendCallbackUri;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/register")
    ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                        HttpServletRequest servletRequest,
                                        HttpServletResponse servletResponse) {
        AuthService.SessionTokens tokens = authService.register(
                request.email(), request.password(), request.displayName(), traceId(servletRequest));
        writeSessionCookies(servletResponse, tokens);
        return ApiResponse.ok(LoginResponse.from(tokens));
    }

    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest servletRequest,
                                     HttpServletResponse servletResponse) {
        AuthService.SessionTokens tokens = authService.login(request.email(), request.password(), traceId(servletRequest));
        writeSessionCookies(servletResponse, tokens);
        return ApiResponse.ok(LoginResponse.from(tokens));
    }

    @PostMapping("/refresh")
    ApiResponse<LoginResponse> refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                                       @CookieValue(value = CSRF_COOKIE, required = false) String csrfCookie,
                                       @RequestHeader(value = "X-CSRF-Token", required = false) String csrfHeader,
                                       HttpServletRequest servletRequest,
                                       HttpServletResponse servletResponse) {
        requireMatchingCsrf(csrfCookie, csrfHeader);
        AuthService.SessionTokens tokens = authService.refresh(refreshToken, csrfHeader, traceId(servletRequest));
        writeSessionCookies(servletResponse, tokens);
        return ApiResponse.ok(LoginResponse.from(tokens));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                             @CookieValue(value = CSRF_COOKIE, required = false) String csrfCookie,
                             @RequestHeader(value = "X-CSRF-Token", required = false) String csrfHeader,
                             HttpServletRequest servletRequest,
                             HttpServletResponse servletResponse) {
        requireMatchingCsrf(csrfCookie, csrfHeader);
        authService.logout(refreshToken, csrfHeader, traceId(servletRequest));
        clearSessionCookies(servletResponse);
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    ApiResponse<UserSession> me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        AuthService.UserSession user = authService.parseAccessToken(authorization);
        return ApiResponse.ok(new UserSession(user.userId(), user.displayName(), user.roles()));
    }

    @GetMapping("/oauth2/github/start")
    ApiResponse<OAuthStartResponse> startGitHubOAuth(HttpServletResponse response) {
        GitHubOAuthService.OAuthStart start = gitHubOAuthService.start();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("dc_oauth_state", start.state())
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/auth/oauth2/github")
                .maxAge(Duration.ofMinutes(10))
                .build().toString());
        return ApiResponse.ok(new OAuthStartResponse(start.authorizationUrl()));
    }

    @GetMapping("/oauth2/github/callback")
    void finishGitHubOAuth(@RequestParam String code,
                           @RequestParam String state,
                           @CookieValue(value = "dc_oauth_state", required = false) String stateCookie,
                           HttpServletRequest request,
                           HttpServletResponse response) throws java.io.IOException {
        requireMatchingCsrf(stateCookie, state);
        try {
            var account = gitHubOAuthService.callback(code, state);
            AuthService.SessionTokens tokens = authService.oauthLogin(account, traceId(request));
            writeSessionCookies(response, tokens);
            clearOAuthStateCookie(response);
            response.sendRedirect(frontendCallbackUri);
        } catch (BusinessException ex) {
            clearOAuthStateCookie(response);
            String failureUri = UriComponentsBuilder.fromUriString(frontendCallbackUri)
                    .replaceQueryParam("error", ex.code().name())
                    .build()
                    .toUriString();
            response.sendRedirect(failureUri);
        }
    }

    private void requireMatchingCsrf(String cookie, String header) {
        if (cookie == null || header == null || !java.security.MessageDigest.isEqual(
                cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new io.github.devcircle.common.error.BusinessException(
                    io.github.devcircle.common.error.ErrorCode.TOKEN_REVOKED, "CSRF 校验失败");
        }
    }

    private void writeSessionCookies(HttpServletResponse response, AuthService.SessionTokens tokens) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), tokens.refreshExpiresAt()).toSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, tokens.refreshToken(), true, maxAge, "/api/auth").toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, tokens.csrfToken(), false, maxAge, "/").toString());
    }

    private void clearSessionCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", true, 0, "/api/auth").toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, "", false, 0, "/").toString());
    }

    private void clearOAuthStateCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("dc_oauth_state", "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/auth/oauth2/github")
                .maxAge(0)
                .build()
                .toString());
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly, long maxAge, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secureCookies)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private String traceId(HttpServletRequest request) {
        return request.getHeader("X-Trace-Id");
    }

    record RegisterRequest(@NotBlank @Email String email,
                           @NotBlank @Size(min = 12, max = 128) String password,
                           @NotBlank @Size(min = 2, max = 32) String displayName) {
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    record LoginResponse(String accessToken, Instant expiresAt, UserSession user) {
        static LoginResponse from(AuthService.SessionTokens tokens) {
            AuthService.UserSession user = tokens.user();
            return new LoginResponse(tokens.accessToken(), tokens.accessExpiresAt(),
                    new UserSession(user.userId(), user.displayName(), user.roles()));
        }
    }

    record UserSession(Long userId, String displayName, List<String> roles) {
    }

    record OAuthStartResponse(String authorizationUrl) {
    }
}
