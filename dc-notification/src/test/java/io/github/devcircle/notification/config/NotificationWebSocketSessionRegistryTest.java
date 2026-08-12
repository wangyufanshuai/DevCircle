package io.github.devcircle.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationWebSocketSessionRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void activeTransportIsClosedAndRemovedAtTheVerifiedTokenExpiry() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        Runnable[] expiry = new Runnable[1];
        when(scheduler.schedule(any(Runnable.class), eq(15_000_000_000L), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    expiry[0] = invocation.getArgument(0);
                    return future;
                });
        NotificationWebSocketSessionRegistry registry = registry(scheduler);
        WebSocketSession session = session("ws-active");

        registry.register(session);
        registry.armExpiry("ws-active", NOW.plusSeconds(15));
        assertThat(registry.size()).isOne();

        expiry[0].run();

        assertThat(registry.size()).isZero();
        verify(session).close(any(CloseStatus.class));
        registry.close();
    }

    @Test
    void expiredLeaseIsRejectedBeforeScheduling() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        NotificationWebSocketSessionRegistry registry = registry(scheduler);
        registry.register(session("ws-expired"));

        assertThatThrownBy(() -> registry.armExpiry("ws-expired", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        verify(scheduler, never()).schedule(
                any(Runnable.class), anyLong(), any(TimeUnit.class));
        registry.close();
    }

    @Test
    void transportCloseCancelsExpiryAndRemovesTheSession() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(
                any(Runnable.class), eq(15_000_000_000L), eq(TimeUnit.NANOSECONDS));
        NotificationWebSocketSessionRegistry registry = registry(scheduler);
        WebSocketSession session = session("ws-cleanup");
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        NotificationWebSocketLifecycleHandler handler =
                new NotificationWebSocketLifecycleHandler(delegate, registry);

        handler.afterConnectionEstablished(session);
        registry.armExpiry("ws-cleanup", NOW.plusSeconds(15));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.size()).isZero();
        verify(future).cancel(false);
        verify(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);
        registry.close();
    }

    @Test
    void registryShutdownCancelsLeaseClosesTransportAndStopsScheduler() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(
                any(Runnable.class), eq(15_000_000_000L), eq(TimeUnit.NANOSECONDS));
        NotificationWebSocketSessionRegistry registry = registry(scheduler);
        WebSocketSession session = session("ws-shutdown");
        registry.register(session);
        registry.armExpiry("ws-shutdown", NOW.plusSeconds(15));

        registry.close();

        assertThat(registry.size()).isZero();
        verify(future).cancel(false);
        verify(session).close(any(CloseStatus.class));
        verify(scheduler).shutdownNow();
    }

    private NotificationWebSocketSessionRegistry registry(ScheduledExecutorService scheduler) {
        return new NotificationWebSocketSessionRegistry(
                Clock.fixed(NOW, ZoneOffset.UTC), scheduler);
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
