package io.github.devcircle.notification.config;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;
import java.time.Clock;
import java.util.List;

final class NotificationStompChannelInterceptor implements ChannelInterceptor {
    static final String NOTIFICATION_DESTINATION = "/user/queue/notifications";
    private static final String BEARER_PREFIX = "Bearer ";

    private final NotificationAccessTokenVerifier tokenVerifier;
    private final NotificationWebSocketSessionRegistry sessions;
    private final Clock clock;

    NotificationStompChannelInterceptor(NotificationAccessTokenVerifier tokenVerifier,
                                        NotificationWebSocketSessionRegistry sessions,
                                        Clock clock) {
        this.tokenVerifier = tokenVerifier;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            // STOMP heartbeats contain no command and must remain valid.
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            authenticateConnect(accessor);
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            return message;
        }
        if (command == StompCommand.SEND) {
            throw denied("Client SEND frames are not supported");
        }
        if (command == StompCommand.SUBSCRIBE) {
            requireAuthenticated(accessor);
            if (!NOTIFICATION_DESTINATION.equals(accessor.getDestination())) {
                throw denied("Only the authenticated notification queue may be subscribed");
            }
            return message;
        }
        if (command == StompCommand.UNSUBSCRIBE) {
            requireAuthenticated(accessor);
            return message;
        }
        throw denied("Unsupported STOMP command");
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        try {
            List<String> values = accessor.getNativeHeader(HttpHeaders.AUTHORIZATION);
            if (values == null || values.size() != 1) {
                throw denied("A single bearer access token is required");
            }
            String authorization = values.getFirst();
            if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                    || authorization.length() == BEARER_PREFIX.length()) {
                throw denied("A bearer access token is required");
            }
            NotificationAccessTokenVerifier.VerifiedAccessToken verified;
            try {
                verified = tokenVerifier.verify(authorization.substring(BEARER_PREFIX.length()));
            } catch (RuntimeException error) {
                // Do not propagate verifier details: they could expose the token through
                // framework exception logging.
                throw denied("The bearer access token is invalid");
            }
            long userId = verified.userId();
            if (userId <= 0) {
                throw denied("The access-token subject is invalid");
            }
            if (verified.expiresAt() == null || !verified.expiresAt().isAfter(clock.instant())) {
                throw denied("The bearer access token is expired");
            }
            String sessionId = accessor.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                throw denied("A WebSocket transport session is required");
            }
            sessions.armExpiry(sessionId, verified.expiresAt());
            accessor.setUser(new NotificationPrincipal(Long.toString(userId)));
        } catch (MessagingException error) {
            throw error;
        } catch (RuntimeException error) {
            throw denied("The bearer access token is invalid");
        } finally {
            // Spring Messaging 7.0.x leaves decoded STOMP headers mutable for inbound
            // interceptors. Scrub the credential on every CONNECT outcome before this
            // Message reaches any downstream channel, broker, executor, or handler.
            accessor.removeNativeHeader(HttpHeaders.AUTHORIZATION);
        }
    }

    private void requireAuthenticated(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null || user.getName() == null || !user.getName().matches("[1-9][0-9]*")) {
            throw denied("An authenticated STOMP session is required");
        }
    }

    private MessagingException denied(String message) {
        return new MessagingException(message);
    }

    private record NotificationPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
