package io.github.devcircle.notification.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationStompChannelInterceptorTest {
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private final AtomicReference<String> tokenSeenByVerifier = new AtomicReference<>();
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final NotificationWebSocketSessionRegistry sessions =
            new NotificationWebSocketSessionRegistry(Clock.fixed(NOW, ZoneOffset.UTC), scheduler);
    private final NotificationStompChannelInterceptor interceptor =
            new NotificationStompChannelInterceptor(token -> {
                tokenSeenByVerifier.set(token);
                if ("past-access".equals(token)) {
                    return new NotificationAccessTokenVerifier.VerifiedAccessToken(42L, NOW);
                }
                if (!"valid-access".equals(token)) throw new IllegalArgumentException("invalid token");
                return new NotificationAccessTokenVerifier.VerifiedAccessToken(
                        42L, NOW.plusSeconds(60));
            }, sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    NotificationStompChannelInterceptorTest() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> expiry = mock(ScheduledFuture.class);
        doReturn(expiry).when(scheduler).schedule(
                any(Runnable.class), anyLong(), any(TimeUnit.class));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-1");
        sessions.register(session);
    }

    @Test
    void connectRequiresOneValidBearerAccessTokenAndSetsNumericPrincipal() {
        Message<?> connected = interceptor.preSend(frame(StompCommand.CONNECT, null, null,
                "Bearer valid-access"), (message, timeout) -> true);

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(connected, StompHeaderAccessor.class);
        assertThat(accessor).isNotNull();
        assertThat(accessor.getUser()).extracting(Principal::getName).isEqualTo("42");
        assertThat(tokenSeenByVerifier).hasValue("valid-access");
        assertThat(accessor.getNativeHeader("Authorization")).isNull();
        assertThat(StompHeaderAccessor.wrap(connected).getNativeHeader("Authorization")).isNull();
        verify(scheduler).schedule(any(Runnable.class),
                eq(TimeUnit.SECONDS.toNanos(60)), eq(TimeUnit.NANOSECONDS));

        assertDenied(frame(StompCommand.CONNECT, null, null, null));
        assertDenied(frame(StompCommand.CONNECT, null, null, "Bearer expired-or-refresh"));
        assertDenied(frame(StompCommand.CONNECT, null, null, "Bearer past-access"));
    }

    @Test
    void invalidBearerIsScrubbedAndVerifierDetailsAreNotPropagated() {
        StompHeaderAccessor accessor = connectAccessor("Bearer rejected-sensitive-value");
        Message<?> rejected = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());

        Throwable error = catchThrowable(() -> interceptor.preSend(rejected,
                (message, timeout) -> true));

        assertThat(tokenSeenByVerifier).hasValue("rejected-sensitive-value");
        assertThat(error)
                .isInstanceOf(MessagingException.class)
                .hasMessage("The bearer access token is invalid")
                .hasNoCause();
        assertThat(error.toString()).doesNotContain("rejected-sensitive-value");
        assertAuthorizationRemoved(accessor, rejected);
    }

    @Test
    void connectRejectsAuthorizationHeaderSmuggling() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer valid-access");
        accessor.addNativeHeader("Authorization", "Bearer second-token");
        accessor.setLeaveMutable(true);
        Message<?> rejected = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());

        assertDenied(rejected);

        assertThat(tokenSeenByVerifier.get()).isNull();
        assertAuthorizationRemoved(accessor, rejected);
    }

    @Test
    void connectRejectsAndScrubsNonBearerAuthorization() {
        StompHeaderAccessor accessor = connectAccessor("Basic sensitive-value");
        Message<?> rejected = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());

        assertDenied(rejected);

        assertThat(tokenSeenByVerifier.get()).isNull();
        assertAuthorizationRemoved(accessor, rejected);
    }

    @Test
    void connectRejectsAndScrubsEmptyBearerAuthorization() {
        StompHeaderAccessor accessor = connectAccessor("Bearer ");
        Message<?> rejected = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());

        assertDenied(rejected);

        assertThat(tokenSeenByVerifier.get()).isNull();
        assertAuthorizationRemoved(accessor, rejected);
    }

    @Test
    void authenticatedUserCanSubscribeOnlyToTheUserNotificationQueue() {
        Message<?> result = interceptor.preSend(frame(StompCommand.SUBSCRIBE,
                NotificationStompChannelInterceptor.NOTIFICATION_DESTINATION,
                () -> "42", null), (message, timeout) -> true);

        assertThat(result).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/queue/notifications",
            "/topic/notifications",
            "/user/42/queue/notifications",
            "/user/queue/other",
            "/app/notifications"
    })
    void rawCrossUserAndApplicationDestinationsAreRejected(String destination) {
        assertDenied(frame(StompCommand.SUBSCRIBE, destination, () -> "42", null));
    }

    @Test
    void subscribeRequiresAnAuthenticatedNumericUserAndClientSendIsAlwaysRejected() {
        assertDenied(frame(StompCommand.SUBSCRIBE,
                NotificationStompChannelInterceptor.NOTIFICATION_DESTINATION, null, null));
        assertDenied(frame(StompCommand.SUBSCRIBE,
                NotificationStompChannelInterceptor.NOTIFICATION_DESTINATION, () -> "not-a-user", null));
        assertDenied(frame(StompCommand.SEND,
                NotificationStompChannelInterceptor.NOTIFICATION_DESTINATION, () -> "42", null));
    }

    @Test
    void heartbeatsAndDisconnectRemainValid() {
        Message<byte[]> heartbeat = MessageBuilder.withPayload(new byte[]{'\n'}).build();
        assertThat(interceptor.preSend(heartbeat, (message, timeout) -> true)).isSameAs(heartbeat);

        Message<?> disconnect = frame(StompCommand.DISCONNECT, null, null, null);
        assertThat(interceptor.preSend(disconnect, (message, timeout) -> true)).isSameAs(disconnect);
    }

    private Message<?> frame(StompCommand command, String destination,
                             Principal principal, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("ws-1");
        if (destination != null) accessor.setDestination(destination);
        if (principal != null) accessor.setUser(principal);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompHeaderAccessor connectAccessor(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("ws-1");
        accessor.addNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private void assertAuthorizationRemoved(StompHeaderAccessor accessor, Message<?> message) {
        assertThat(accessor.getNativeHeader("Authorization")).isNull();
        assertThat(StompHeaderAccessor.wrap(message).getNativeHeader("Authorization")).isNull();
    }

    private void assertDenied(Message<?> message) {
        assertThatThrownBy(() -> interceptor.preSend(message, (ignored, timeout) -> true))
                .isInstanceOf(MessagingException.class);
    }
}
