package io.github.devcircle.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
class TraceFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAt = System.currentTimeMillis();
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        ServerHttpRequest request = exchange.getRequest().mutate().header("X-Trace-Id", traceId).build();
        exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);
        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signalType -> {
                    String routeId = String.valueOf(exchange.getAttributeOrDefault("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoute", ""));
                    String userId = request.getHeaders().getFirst("X-User-Id");
                    int status = exchange.getResponse().getStatusCode() == null ? 0 : exchange.getResponse().getStatusCode().value();
                    log.info("gateway_request traceId={} userId={} method={} path={} status={} durationMs={} route={} signal={}",
                            finalTraceId,
                            userId == null ? "-" : userId,
                            request.getMethod(),
                            request.getPath().value(),
                            status,
                            System.currentTimeMillis() - startedAt,
                            routeId,
                            signalType);
                });
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
