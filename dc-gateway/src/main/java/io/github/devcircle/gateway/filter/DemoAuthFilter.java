package io.github.devcircle.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class DemoAuthFilter implements GlobalFilter, Ordered {
    static final String USER_TOKEN = "Bearer demo-access-token-demo";
    static final String ADMIN_TOKEN = "Bearer demo-access-token-admin";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        if (path.startsWith("/api/admin/") && !ADMIN_TOKEN.equals(authorization)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        var roles = ADMIN_TOKEN.equals(authorization) ? "ADMIN,USER" : "USER";
        var userId = ADMIN_TOKEN.equals(authorization) ? "1" : "2";
        var request = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-Roles", roles)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
