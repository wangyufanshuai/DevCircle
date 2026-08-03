package io.github.devcircle.common.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedRestClientFactoryTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void trimsTrailingSlashesAndReadsHealthyResponses() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, "ok"));
        server.start();

        String body = BoundedRestClientFactory.create(baseUrl() + "///")
                .get().uri("/health").retrieve().body(String.class);

        assertThat(body).isEqualTo("ok");
    }

    @Test
    void appliesTheConfiguredReadTimeout() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, "late");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();

        var client = BoundedRestClientFactory.create(baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(50));

        assertThatThrownBy(() -> client.get().uri("/slow").retrieve().body(String.class))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void rejectsInvalidConfigurationBeforeBuildingAClient() {
        assertThatThrownBy(() -> BoundedRestClientFactory.create(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base URL");
        assertThatThrownBy(() -> BoundedRestClientFactory.create("http://localhost", Duration.ZERO,
                Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> BoundedRestClientFactory.customize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Builder");
        assertThatThrownBy(() -> BoundedRestClientFactory.create("http://localhost", Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeout");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
