package io.github.devcircle.gateway.filter;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

final class GatewayResponses {
    private GatewayResponses() {
    }

    static Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null) traceId = "";
        String json = """
                {"code":"%s","message":"%s","traceId":"%s"}
                """.formatted(code, message, traceId).trim();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setContentLength(bytes.length);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
