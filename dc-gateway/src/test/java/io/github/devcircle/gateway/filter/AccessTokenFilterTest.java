package io.github.devcircle.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenFilterTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-production-style-validation-123456";
    private final AccessTokenFilter filter = new AccessTokenFilter(SECRET, "", "");

    @Test
    void productionRejectsPlaceholderAndLowDiversitySecrets() {
        assertThatThrownBy(() -> new AccessTokenFilter(
                "replace-with-at-least-64-random-characters-replace-with-placeholder", "", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
        assertThatThrownBy(() -> new AccessTokenFilter("a".repeat(72), "", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production");
    }

    @Test
    void productionAcceptsLongHighDiversitySecret() {
        assertThatCode(() -> new AccessTokenFilter(
                "Aa1!Bb2@Cc3#Dd4$Ee5%Ff6^Gg7&Hh8*Ii9(Jj0)Kk1_Ll2+Mm3-Nn4=Oo5?Pp6Qq7~", "", "", true))
                .doesNotThrowAnyException();
    }

    @Test
    void anonymousReadersCanBrowsePublishedContent() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts").build());
        AtomicReference<String> userId = new AtomicReference<>();

        filter.filter(exchange, captureUser(userId)).block();

        assertThat(userId).hasValue("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/posts/7",
            "/api/posts/7/related",
            "/api/posts/7/comments",
            "/api/posts/summaries",
            "/api/search/posts",
            "/api/users/7",
            "/api/users/batch",
            "/api/files/access",
            "/api/files/7/access"
    })
    void anonymousReadersCanBrowseOnlyExplicitPublicPostResources(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        AtomicReference<String> userId = new AtomicReference<>();

        filter.filter(exchange, captureUser(userId)).block();

        assertThat(userId).hasValue("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/posts/drafts",
            "/api/posts/drafts/7",
            "/api/posts/drafts/7/audits",
            "/api/posts/7/interaction-state",
            "/api/users/7/follow-state",
            "/api/users/7/recommendations",
            "/api/search/admin/reindex",
            "/api/files/private/7/access"
    })
    void anonymousReadersCannotReachProtectedPostResources(String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anonymousWritesAreRejected() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/posts").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validAccessTokenPropagatesTrustedIdentity() {
        String token = Jwts.builder().subject("7").claim("roles", "USER").claim("type", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "post-create-7")
                .header("X-User-Id", "999")
                .build());
        AtomicReference<String> userId = new AtomicReference<>();

        filter.filter(exchange, captureUser(userId)).block();

        assertThat(userId).hasValue("7");
    }

    @Test
    void authenticatedWriteWithoutIdempotencyKeyIsRejected() {
        String token = Jwts.builder().subject("7").claim("roles", "USER").claim("type", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("VALIDATION_FAILED");
    }

    @Test
    void authenticationWriteWithoutIdempotencyKeyIsRejectedBeforeRouting() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("Idempotency-Key");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/metrics/overview",
            "/api/search/admin/reindex",
            "/api/content/admin/outbox/publish"
    })
    void nonAdminCannotReachAdminRoutes(String path) {
        String token = Jwts.builder().subject("7").claim("roles", "USER").claim("type", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var request = path.equals("/api/admin/metrics/overview")
                ? MockServerHttpRequest.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                : MockServerHttpRequest.post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "admin-operation");
        var exchange = MockServerWebExchange.from(request.build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/search/admin/reindex",
            "/api/content/admin/outbox/publish"
    })
    void adminCanReachInternalOperationsWithAWriteKey(String path) {
        String token = Jwts.builder().subject("9").claim("roles", "USER,ADMIN").claim("type", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", "admin-operation")
                .build());
        AtomicReference<String> userId = new AtomicReference<>();

        filter.filter(exchange, captureUser(userId)).block();

        assertThat(userId).hasValue("9");
    }

    private GatewayFilterChain captureUser(AtomicReference<String> userId) {
        return exchange -> {
            userId.set(exchange.getRequest().getHeaders().getFirst("X-User-Id"));
            return Mono.empty();
        };
    }
}
