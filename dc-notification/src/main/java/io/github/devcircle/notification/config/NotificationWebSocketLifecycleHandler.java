package io.github.devcircle.notification.config;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

final class NotificationWebSocketLifecycleHandler extends WebSocketHandlerDecorator {
    private final NotificationWebSocketSessionRegistry sessions;

    NotificationWebSocketLifecycleHandler(WebSocketHandler delegate,
                                          NotificationWebSocketSessionRegistry sessions) {
        super(delegate);
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.register(session);
        try {
            super.afterConnectionEstablished(session);
        } catch (Exception error) {
            sessions.unregister(session.getId());
            throw error;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
            throws Exception {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            sessions.unregister(session.getId());
        }
    }
}
