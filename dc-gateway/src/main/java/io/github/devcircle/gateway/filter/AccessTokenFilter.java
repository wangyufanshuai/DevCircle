package io.github.devcircle.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class AccessTokenFilter implements GlobalFilter, Ordered {
    private static final Pattern PUBLIC_POST_READ_PATH = Pattern.compile(
            "^/api/posts(?:/?|/summaries/?|/\\d+(?:/?|/related/?|/comments/?))$");
    private static final Pattern PUBLIC_FILE_READ_PATH = Pattern.compile(
            "^/api/files(?:/access|/\\d+/access)/?$");
    private static final Pattern PUBLIC_USER_READ_PATH = Pattern.compile(
            "^/api/users(?:/batch/?|/\\d+/?)$");
    private final SecretKey jwtKey;
    private final Set<String> bannedUserIds;
    private final Set<String> noPostUserIds;

    public AccessTokenFilter(String secret, String bannedUserIds, String noPostUserIds) {
        this(secret, bannedUserIds, noPostUserIds, false);
    }

    @Autowired
    public AccessTokenFilter(@Value("${devcircle.jwt.secret}") String secret,
                             @Value("${devcircle.gateway.banned-user-ids:}") String bannedUserIds,
                             @Value("${devcircle.gateway.no-post-user-ids:}") String noPostUserIds,
                             @Value("${devcircle.production:false}") boolean production) {
        validateSecret(secret, production);
        this.jwtKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.bannedUserIds = configuredIds(bannedUserIds);
        this.noPostUserIds = configuredIds(noPostUserIds);
    }

    private static void validateSecret(String secret, boolean production) {
        String normalized = secret == null ? "" : secret.toLowerCase(Locale.ROOT);
        if (secret == null || secret.length() < 48 || normalized.contains("change-me")) {
            throw new IllegalStateException("DEVCIRCLE_JWT_SECRET must contain at least 48 non-default characters");
        }
        if (production && (secret.length() < 64
                || normalized.contains("replace-with")
                || normalized.contains("local-development")
                || normalized.contains("example")
                || normalized.contains("default")
                || secret.codePoints().distinct().count() < 16)) {
            throw new IllegalStateException(
                    "DEVCIRCLE_JWT_SECRET must contain at least 64 high-diversity non-placeholder characters in production");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator/")) {
            return chain.filter(stripForwardedIdentity(exchange));
        }
        if (path.startsWith("/api/auth/")) {
            if (isWriteRequest(exchange) && !hasIdempotencyKey(exchange)) {
                return GatewayResponses.error(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "认证写操作必须提供 Idempotency-Key");
            }
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
        if (isAdminPath(path) && !context.roles().contains("ADMIN")) {
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
        return PUBLIC_POST_READ_PATH.matcher(path).matches()
                || PUBLIC_USER_READ_PATH.matcher(path).matches()
                || path.equals("/api/search/posts")
                || PUBLIC_FILE_READ_PATH.matcher(path).matches()
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

    private boolean isAdminPath(String path) {
        return path.startsWith("/api/admin/")
                || path.startsWith("/api/search/admin/")
                || path.startsWith("/api/content/admin/");
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
