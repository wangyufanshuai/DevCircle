package io.github.devcircle.notification.config;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks only live transport sessions and their expiry leases. Access tokens are intentionally
 * never stored. The registry is package-private infrastructure owned by the notification runtime.
 */
final class NotificationWebSocketSessionRegistry implements AutoCloseable {
    private static final CloseStatus TOKEN_EXPIRED =
            new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "Access token expired");

    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, SessionLease> sessions = new ConcurrentHashMap<>();

    NotificationWebSocketSessionRegistry() {
        this(Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "notification-websocket-expiry");
            thread.setDaemon(true);
            return thread;
        }));
    }

    NotificationWebSocketSessionRegistry(Clock clock, ScheduledExecutorService scheduler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    void register(WebSocketSession session) {
        Objects.requireNonNull(session, "session");
        SessionLease replaced = sessions.put(session.getId(), new SessionLease(session, null, null));
        cancel(replaced);
    }

    void armExpiry(String sessionId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Access token is already expired");
        }
        sessions.compute(sessionId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("WebSocket transport session is not registered");
            }
            cancel(existing);
            long delayNanos = Math.max(0L, Duration.between(clock.instant(), expiresAt).toNanos());
            ScheduledFuture<?> expiryTask = scheduler.schedule(
                    () -> expire(sessionId, expiresAt), delayNanos, TimeUnit.NANOSECONDS);
            return new SessionLease(existing.session(), expiresAt, expiryTask);
        });
    }

    void unregister(String sessionId) {
        cancel(sessions.remove(sessionId));
    }

    int size() {
        return sessions.size();
    }

    private void expire(String sessionId, Instant expectedExpiry) {
        WebSocketSession[] expiredSession = new WebSocketSession[1];
        sessions.computeIfPresent(sessionId, (ignored, lease) -> {
            if (!expectedExpiry.equals(lease.expiresAt())) return lease;
            expiredSession[0] = lease.session();
            return null;
        });
        closeQuietly(expiredSession[0]);
    }

    @Override
    public void close() {
        ArrayList<SessionLease> active = new ArrayList<>(sessions.values());
        sessions.clear();
        active.forEach(lease -> {
            cancel(lease);
            closeQuietly(lease.session());
        });
        scheduler.shutdownNow();
    }

    private static void cancel(SessionLease lease) {
        if (lease != null && lease.expiryTask() != null) lease.expiryTask().cancel(false);
    }

    private static void closeQuietly(WebSocketSession session) {
        if (session == null || !session.isOpen()) return;
        try {
            session.close(TOKEN_EXPIRED);
        } catch (IOException ignored) {
            // The transport is already unusable; lifecycle cleanup has still removed the lease.
        }
    }

    private record SessionLease(WebSocketSession session, Instant expiresAt,
                                ScheduledFuture<?> expiryTask) {
    }
}
