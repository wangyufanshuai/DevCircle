package io.github.devcircle.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAuthFilterTest {
    private final DemoAuthFilter filter = new DemoAuthFilter();

    @Test
    void rejectsMissingTokenForProtectedApi() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts").build());
        filter.filter(exchange, passThrough()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requiresAdminTokenForAdminApi() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/audits/posts")
                .header(HttpHeaders.AUTHORIZATION, DemoAuthFilter.USER_TOKEN)
                .build());
        filter.filter(exchange, passThrough()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsAuthApiWithoutToken() {
        AtomicBoolean called = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/login").build());
        filter.filter(exchange, chain(called)).block();

        assertThat(called).isTrue();
    }

    @Test
    void propagatesDemoUserContext() {
        AtomicBoolean called = new AtomicBoolean(false);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts")
                .header(HttpHeaders.AUTHORIZATION, DemoAuthFilter.ADMIN_TOKEN)
                .build());

        filter.filter(exchange, mutated -> {
            called.set(true);
            assertThat(mutated.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("1");
            assertThat(mutated.getRequest().getHeaders().getFirst("X-Roles")).isEqualTo("ADMIN,USER");
            return Mono.empty();
        }).block();

        assertThat(called).isTrue();
    }

    private GatewayFilterChain passThrough() {
        return exchange -> Mono.empty();
    }

    private GatewayFilterChain chain(AtomicBoolean called) {
        return exchange -> {
            called.set(true);
            return Mono.empty();
        };
    }
}
