package io.github.devcircle.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
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

class AccessTokenFilterTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-production-style-validation-123456";
    private final AccessTokenFilter filter = new AccessTokenFilter(SECRET, "", "");

    @Test
    void anonymousReadersCanBrowsePublishedContent() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts").build());
        AtomicReference<String> userId = new AtomicReference<>();

        filter.filter(exchange, captureUser(userId)).block();

        assertThat(userId).hasValue("0");
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

    @Test
    void nonAdminCannotReachAdminRoutes() {
        String token = Jwts.builder().subject("7").claim("roles", "USER").claim("type", "access")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/metrics/overview")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private GatewayFilterChain captureUser(AtomicReference<String> userId) {
        return exchange -> {
            userId.set(exchange.getRequest().getHeaders().getFirst("X-User-Id"));
            return Mono.empty();
        };
    }
}
