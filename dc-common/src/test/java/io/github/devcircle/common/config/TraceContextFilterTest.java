package io.github.devcircle.common.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceContextFilterTest {
    private final TraceContextFilter filter = new TraceContextFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesTraceAndUserContextOnlyForTheCurrentRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Trace-Id", "trace-reader-42");
        request.addHeader("X-User-Id", "42");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-reader-42");
            assertThat(MDC.get("userId")).isEqualTo("42");
        });

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-reader-42");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void generatesTraceAndIgnoresBlankUserContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Trace-Id", "  ");
        request.addHeader("X-User-Id", "  ");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get("traceId")).isNotBlank();
            assertThat(MDC.get("userId")).isNull();
        });

        assertThat(response.getHeader("X-Trace-Id"))
                .satisfies(value -> assertThatCodeIsUuid(value));
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void clearsContextWhenDownstreamFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get("traceId")).isNotBlank();
            throw new ServletException("downstream failed");
        })).isInstanceOf(ServletException.class).hasMessage("downstream failed");

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    private static void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
