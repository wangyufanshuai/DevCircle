package io.github.devcircle.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AccessTokenFilter implements GlobalFilter, Ordered {
    private final SecretKey jwtKey;
    private final Set<String> bannedUserIds;
    private final Set<String> noPostUserIds;

    public AccessTokenFilter(@Value("${devcircle.jwt.secret}") String secret,
                             @Value("${devcircle.gateway.banned-user-ids:}") String bannedUserIds,
                             @Value("${devcircle.gateway.no-post-user-ids:}") String noPostUserIds) {
        if (secret == null || secret.length() < 48 || secret.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        this.jwtKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.bannedUserIds = configuredIds(bannedUserIds);
        this.noPostUserIds = configuredIds(noPostUserIds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator/")) {
            return chain.filter(stripForwardedIdentity(exchange));
        }

        UserContext context = userContext(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (context == null && isPublicRead(exchange, path)) {
            context = new UserContext("0", Set.of("ANONYMOUS"));
        }
        if (context == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        if (isWriteRequest(exchange) && !path.startsWith("/api/auth/")
                && !hasIdempotencyKey(exchange)) {
            return GatewayResponses.error(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "写操作必须提供 Idempotency-Key");
        }
        if (path.startsWith("/api/admin/") && !context.roles().contains("ADMIN")) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        if (bannedUserIds.contains(context.userId()) && isWriteRequest(exchange)) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        if (noPostUserIds.contains(context.userId())
                && HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && path.equals("/api/posts")) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        UserContext finalContext = context;
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove("X-Roles");
            headers.set("X-User-Id", finalContext.userId());
            headers.set("X-Roles", String.join(",", finalContext.roles()));
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private ServerWebExchange stripForwardedIdentity(ServerWebExchange exchange) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove("X-Roles");
        }).build();
        return exchange.mutate().request(request).build();
    }

    private UserContext userContext(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(jwtKey).build()
                    .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
            if (!"access".equals(claims.get("type", String.class))) {
                return null;
            }
            String roles = claims.get("roles", String.class);
            Set<String> roleSet = roles == null ? Set.of() : Arrays.stream(roles.split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
            return new UserContext(claims.getSubject(), roleSet);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isPublicRead(ServerWebExchange exchange, String path) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        return path.startsWith("/api/posts")
                || path.startsWith("/api/search")
                || (path.startsWith("/api/files/") && path.endsWith("/access"))
                || path.equals("/api/recommend/feed")
                || path.equals("/api/deduction/leaderboard");
    }

    private boolean isWriteRequest(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method) || HttpMethod.DELETE.equals(method);
    }

    private boolean hasIdempotencyKey(ServerWebExchange exchange) {
        String value = exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    private Set<String> configuredIds(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(",")).map(String::trim)
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED
                ? GatewayResponses.error(exchange, status, "UNAUTHORIZED", "请先登录后再继续")
                : GatewayResponses.error(exchange, status, "FORBIDDEN", "当前账号没有执行此操作的权限");
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private record UserContext(String userId, Set<String> roles) {
    }
}
