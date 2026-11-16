package com.tabletophelper.identity.internal;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Wraps the STOMP handler to record each socket in {@link WebSocketSessions} while it is open, so the server can
 * close it (D238). The session it sees has the same id as the STOMP session id the inbound frames carry, with SockJS
 * as without.
 */
class WebSocketSessionTracker extends WebSocketHandlerDecorator {

    private final WebSocketSessions sessions;

    WebSocketSessionTracker(WebSocketHandler delegate, WebSocketSessions sessions) {
        super(delegate);
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Recorded before the STOMP handler starts, so the session is known before its CONNECT frame arrives
        sessions.opened(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            sessions.closed(session.getId());
        }
    }
}
