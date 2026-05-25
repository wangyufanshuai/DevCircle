package io.github.devcircle.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class DemoAuthFilter implements GlobalFilter, Ordered {
    static final String USER_TOKEN = "Bearer demo-access-token-demo";
    static final String ADMIN_TOKEN = "Bearer demo-access-token-admin";
    private final SecretKey jwtKey;

    public DemoAuthFilter() {
        this("devcircle-local-demo-secret-devcircle-local-demo-secret");
    }

    public DemoAuthFilter(@Value("${devcircle.jwt.secret:devcircle-local-demo-secret-devcircle-local-demo-secret}") String secret) {
        this.jwtKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

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
        UserContext context = userContext(authorization);
        if (context == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        if (path.startsWith("/api/admin/") && !context.roles().contains("ADMIN")) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        var request = exchange.getRequest().mutate()
                .header("X-User-Id", context.userId())
                .header("X-Roles", context.roles())
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private UserContext userContext(String authorization) {
        if (ADMIN_TOKEN.equals(authorization)) {
            return new UserContext("1", "ADMIN,USER");
        }
        if (USER_TOKEN.equals(authorization)) {
            return new UserContext("2", "USER");
        }
        try {
            String token = authorization.replace("Bearer ", "");
            Claims claims = Jwts.parser().verifyWith(jwtKey).build().parseSignedClaims(token).getPayload();
            return new UserContext(claims.getSubject(), claims.get("roles", String.class));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private record UserContext(String userId, String roles) {
    }
}
