package io.github.devcircle.notification.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "devcircle.notification-runtime", name = "websocket-enabled",
        havingValue = "true")
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String allowedOrigin;
    private final NotificationStompChannelInterceptor inboundInterceptor;
    private final NotificationWebSocketSessionRegistry sessions;

    @Autowired
    WebSocketConfig(@Value("${devcircle.notification-runtime.allowed-origin:${devcircle.public-origin:http://localhost:5501}}")
                    String allowedOrigin,
                    NotificationAccessTokenVerifier tokenVerifier) {
        this(allowedOrigin, tokenVerifier, new NotificationWebSocketSessionRegistry(),
                Clock.systemUTC());
    }

    WebSocketConfig(String allowedOrigin, NotificationAccessTokenVerifier tokenVerifier,
                    NotificationWebSocketSessionRegistry sessions, Clock clock) {
        this.allowedOrigin = requireConcreteHttpOrigin(allowedOrigin);
        this.sessions = sessions;
        this.inboundInterceptor = new NotificationStompChannelInterceptor(tokenVerifier, sessions, clock);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications").setAllowedOrigins(allowedOrigin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(inboundInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(
                handler -> new NotificationWebSocketLifecycleHandler(handler, sessions));
    }

    @PreDestroy
    void shutdownExpiryScheduler() {
        sessions.close();
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
