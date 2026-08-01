package io.github.devcircle.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceFilterTest {
    private final TraceFilter filter = new TraceFilter();

    @Test
    void preservesAValidTraceIdAcrossRequestAndResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts")
                .header("X-Trace-Id", "trace.reader-7:request_1")
                .build());
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, routed -> {
            forwarded.set(routed.getRequest().getHeaders().getFirst("X-Trace-Id"));
            return Mono.empty();
        }).block();

        assertThat(forwarded).hasValue("trace.reader-7:request_1");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo("trace.reader-7:request_1");
    }

    @Test
    void replacesUnsafeOrOversizedClientTraceIds() {
        assertGeneratedTrace("invalid\"trace");
        assertGeneratedTrace("x".repeat(129));
        assertGeneratedTrace(" leading-space");
    }

    private void assertGeneratedTrace(String supplied) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/posts")
                .header("X-Trace-Id", supplied)
                .build());
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, routed -> {
            forwarded.set(routed.getRequest().getHeaders().getFirst("X-Trace-Id"));
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotEqualTo(supplied);
        assertThatCodePointUuid(forwarded.get());
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo(forwarded.get());
    }

    private void assertThatCodePointUuid(String value) {
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }
}
