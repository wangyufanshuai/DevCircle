package io.github.devcircle.interaction.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPostPublicationGatewayTest {
    private HttpServer server;
    private HttpPostPublicationGateway gateway;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/posts/1001", exchange -> respond(exchange, 200,
                "{\"code\":\"OK\",\"data\":{\"id\":1001,\"status\":\"PUBLISHED\",\"likes\":17}}"));
        server.createContext("/api/posts/1002", exchange -> respond(exchange, 404,
                "{\"code\":\"RESOURCE_NOT_FOUND\",\"data\":null}"));
        server.createContext("/api/posts/1003", exchange -> respond(exchange, 200,
                "{\"code\":\"OK\",\"data\":{\"id\":1003,\"status\":\"PENDING\"}}"));
        server.start();
        gateway = new HttpPostPublicationGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsOnlyPublishedContent() {
        assertThat(gateway.requirePublished(1001L).likeCount()).isEqualTo(17);
        assertThatThrownBy(() -> gateway.requirePublished(1002L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> gateway.requirePublished(1003L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void reportsDependencyFailureInsteadOfAllowingAWrite() {
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> gateway.requirePublished(1001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
