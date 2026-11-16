package com.tabletophelper.identity.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Closes WebSocket connections whose access token has expired, checking every
 * {@code app.websocket.expiry-sweep-interval} (D238). The client reconnects shortly before its token expires, so a
 * sweep usually finds nothing; it catches clients that did not. Between sweeps {@link WebSocketAuthInterceptor}
 * refuses SUBSCRIBE and SEND frames on an expired connection. The app has no scheduling enabled, so this runs its
 * own daemon thread, started and stopped with the context.
 */
@Slf4j
@Component
class WebSocketExpirySweeper implements SmartLifecycle {

    private final WebSocketSessions sessions;
    private final Duration interval;
    private ScheduledExecutorService executor;

    WebSocketExpirySweeper(WebSocketSessions sessions,
                           @Value("${app.websocket.expiry-sweep-interval:15s}") Duration interval) {
        this.sessions = sessions;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "websocket-expiry-sweep");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::sweep, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    // An exception would cancel the repeating task, so none escapes
    private void sweep() {
        try {
            int closed = sessions.closeExpired();
            if (closed > 0) {
                log.debug("Closed {} WebSocket session(s) with an expired access token", closed);
            }
        } catch (RuntimeException e) {
            log.warn("WebSocket expiry sweep failed", e);
        }
    }
}
