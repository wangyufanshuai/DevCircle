package io.github.devcircle.gateway.filter;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    private static final InetSocketAddress INTERNAL_PROXY = new InetSocketAddress("172.18.0.9", 8080);
    private final SharedFakeStore sharedStore = new SharedFakeStore();
    private final RateLimitFilter filter = new RateLimitFilter(sharedStore, new SimpleMeterRegistry());

    @Test
    void trustedProxyClientsReceiveIndependentAuthenticationBudgets() {
        for (int request = 0; request < 20; request++) {
            assertAllowed(authRequest(INTERNAL_PROXY, "203.0.113.7, 172.18.0.2"));
            assertAllowed(authRequest(INTERNAL_PROXY, "203.0.113.8, 172.18.0.2"));
        }

        assertLimited(authRequest(INTERNAL_PROXY, "203.0.113.7, 172.18.0.2"));
        assertLimited(authRequest(INTERNAL_PROXY, "203.0.113.8, 172.18.0.2"));
    }

    @Test
    void directPublicClientCannotRotateForwardedHeadersToBypassLimit() {
        InetSocketAddress directClient = new InetSocketAddress("198.51.100.20", 5510);
        for (int request = 0; request < 20; request++) {
            assertAllowed(authRequest(directClient, "203.0.113." + request));
        }

        assertLimited(authRequest(directClient, "192.0.2.99"));
    }

    @Test
    void invalidForwardedAddressFallsBackToTheProxyPeer() {
        for (int request = 0; request < 20; request++) {
            assertAllowed(authRequest(INTERNAL_PROXY, "not-an-ip-" + request));
        }

        assertLimited(authRequest(INTERNAL_PROXY, "still-not-an-ip"));
    }

    @Test
    void separateFilterInstancesShareTheSameCounterStore() {
        RateLimitFilter second = new RateLimitFilter(sharedStore, new SimpleMeterRegistry());
        for (int request = 0; request < 19; request++) {
            assertAllowed(authRequest(INTERNAL_PROXY, "203.0.113.9"));
        }
        assertAllowedWith(second, authRequest(INTERNAL_PROXY, "203.0.113.9"));
        assertLimitedWith(second, authRequest(INTERNAL_PROXY, "203.0.113.9"));
    }

    @Test
    void storeFailureFailsClosedWithoutCallingDownstream() {
        RateLimitFilter unavailable = new RateLimitFilter(
                (key, expiry) -> Mono.error(new IllegalStateException("redis down")), new SimpleMeterRegistry());
        var exchange = authRequest(INTERNAL_PROXY, "203.0.113.10");
        AtomicInteger downstreamCalls = new AtomicInteger();

        unavailable.filter(exchange, ignored -> {
            downstreamCalls.incrementAndGet();
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("RATE_LIMIT_DEPENDENCY_UNAVAILABLE");
        assertThat(downstreamCalls).hasValue(0);
    }

    @Test
    void recordsAllowedLimitedAndDependencyFailureOutcomes() {
        var metrics = new SimpleMeterRegistry();
        RateLimitFilter target = new RateLimitFilter(sharedStore, metrics);
        assertAllowedWith(target, authRequest(INTERNAL_PROXY, "203.0.113.11"));
        for (int request = 0; request < 21; request++) {
            target.filter(authRequest(INTERNAL_PROXY, "203.0.113.12"), ignored -> Mono.empty()).block();
        }
        RateLimitFilter unavailable = new RateLimitFilter((key, expiry) -> Mono.error(new IllegalStateException("redis down")), metrics);
        unavailable.filter(authRequest(INTERNAL_PROXY, "203.0.113.13"), ignored -> Mono.empty()).block();

        assertThat(metrics.get("devcircle.gateway.rate.limit")
                .tag("bucket", "auth").tag("outcome", "allowed").counter().count()).isEqualTo(21.0);
        assertThat(metrics.get("devcircle.gateway.rate.limit")
                .tag("bucket", "auth").tag("outcome", "limited").counter().count()).isEqualTo(1.0);
        assertThat(metrics.get("devcircle.gateway.rate.limit")
                .tag("bucket", "auth").tag("outcome", "dependency_unavailable").counter().count()).isEqualTo(1.0);
    }

    private MockServerWebExchange authRequest(InetSocketAddress remote, String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(remote)
                .header("X-Forwarded-For", forwardedFor)
                .build());
    }

    private void assertAllowed(MockServerWebExchange exchange) {
        assertAllowedWith(filter, exchange);
    }

    private void assertAllowedWith(RateLimitFilter target, MockServerWebExchange exchange) {
        target.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    private void assertLimited(MockServerWebExchange exchange) {
        assertLimitedWith(filter, exchange);
    }

    private void assertLimitedWith(RateLimitFilter target, MockServerWebExchange exchange) {
        target.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("RATE_LIMITED");
    }

    private static final class SharedFakeStore implements RateLimitStore {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public Mono<Integer> increment(String key, Duration expiry) {
            return Mono.just(counts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet());
        }
    }
}
