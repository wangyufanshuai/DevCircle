package io.github.devcircle.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.net.URI;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "devcircle.notification-runtime", name = "websocket-enabled",
        havingValue = "true", matchIfMissing = true)
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String allowedOrigin;

    WebSocketConfig(@Value("${devcircle.notification-runtime.allowed-origin:${devcircle.public-origin:http://localhost:5501}}")
                    String allowedOrigin) {
        this.allowedOrigin = requireConcreteHttpOrigin(allowedOrigin);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications").setAllowedOrigins(allowedOrigin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    private static String requireConcreteHttpOrigin(String value) {
        try {
            URI origin = URI.create(value == null ? "" : value.trim());
            String scheme = origin.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            boolean rootOnly = origin.getPath() == null || origin.getPath().isBlank()
                    || "/".equals(origin.getPath());
            if (!http || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null || !rootOnly
                    || value.contains("*")) {
                throw new IllegalArgumentException("not a concrete HTTP origin");
            }
            return origin.toString().replaceAll("/$", "");
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Notification WebSocket allowed origin must be one concrete HTTP(S) origin", error);
        }
    }
}
