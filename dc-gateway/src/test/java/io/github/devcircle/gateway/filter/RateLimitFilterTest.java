package io.github.devcircle.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    private static final InetSocketAddress INTERNAL_PROXY = new InetSocketAddress("172.18.0.9", 8080);
    private final RateLimitFilter filter = new RateLimitFilter();

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

    private MockServerWebExchange authRequest(InetSocketAddress remote, String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(remote)
                .header("X-Forwarded-For", forwardedFor)
                .build());
    }

    private void assertAllowed(MockServerWebExchange exchange) {
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    private void assertLimited(MockServerWebExchange exchange) {
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("RATE_LIMITED");
    }
}
