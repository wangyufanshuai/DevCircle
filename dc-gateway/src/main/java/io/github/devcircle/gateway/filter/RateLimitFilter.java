package io.github.devcircle.gateway.filter;

import io.netty.util.NetUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

@Component
class RateLimitFilter implements GlobalFilter, Ordered {
    private static final String KEY_PREFIX = "devcircle:gateway:rate-limit:v1:";
    private final RateLimitStore store;

    @Autowired
    RateLimitFilter(RateLimitStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Instant now = Instant.now();
        long minute = now.getEpochSecond() / 60;
        String bucket = bucket(exchange);
        int limit = limit(bucket);
        String key = KEY_PREFIX + clientIdentity(exchange) + ':' + bucket + ':' + minute;
        Duration expiry = Duration.ofSeconds(Math.max(1, 60 - (now.getEpochSecond() % 60)));

        Mono<Integer> count = store.increment(key, expiry)
                .onErrorMap(error -> new RateLimitStoreUnavailableException(error));
        return count.flatMap(used -> {
            exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
            if (used > limit) {
                exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(expiry.toSeconds()));
                return GatewayResponses.error(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "请求过于频繁，请稍后再试");
            }
            return chain.filter(exchange);
        }).onErrorResume(RateLimitStoreUnavailableException.class,
                ignored -> GatewayResponses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "RATE_LIMIT_DEPENDENCY_UNAVAILABLE", "服务暂时不可用，请稍后重试"));
    }

    private String bucket(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/auth/")) return "auth";
        return isWrite(exchange) ? "write" : "read";
    }

    private int limit(String bucket) {
        return switch (bucket) {
            case "auth" -> 20;
            case "write" -> 60;
            default -> 240;
        };
    }

    private String clientIdentity(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return "unknown";
        InetAddress peer = remote.getAddress();
        if (peer != null && (peer.isSiteLocalAddress() || peer.isLoopbackAddress())) {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null) {
                String candidate = forwarded.split(",", 2)[0].trim();
                if (NetUtil.isValidIpV4Address(candidate) || NetUtil.isValidIpV6Address(candidate)) {
                    return candidate;
                }
            }
        }
        return peer == null ? remote.getHostString() : peer.getHostAddress();
    }

    private boolean isWrite(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method) || HttpMethod.DELETE.equals(method);
    }

    @Override
    public int getOrder() {
        return -95;
    }

    private static final class RateLimitStoreUnavailableException extends RuntimeException {
        private RateLimitStoreUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
