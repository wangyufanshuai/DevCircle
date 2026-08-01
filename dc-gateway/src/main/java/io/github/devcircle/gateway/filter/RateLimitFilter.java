package io.github.devcircle.gateway.filter;

import io.netty.util.NetUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class RateLimitFilter implements GlobalFilter, Ordered {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long minute = Instant.now().getEpochSecond() / 60;
        String client = clientIdentity(exchange);
        String path = exchange.getRequest().getPath().value();
        int limit = path.startsWith("/api/auth/") ? 20 : isWrite(exchange) ? 60 : 240;
        String key = client + ':' + (path.startsWith("/api/auth/") ? "auth" : isWrite(exchange) ? "write" : "read");
        Window window = windows.compute(key, (ignored, current) -> current == null || current.minute() != minute
                ? new Window(minute, new AtomicInteger()) : current);
        int used = window.requests().incrementAndGet();
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        if (used > limit) {
            exchange.getResponse().getHeaders().set("Retry-After", "60");
            return GatewayResponses.error(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后再试");
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 2);
        }
        return chain.filter(exchange);
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

    private record Window(long minute, AtomicInteger requests) {
    }
}
