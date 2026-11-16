package com.tabletophelper.identity.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Adds {@link WebSocketAuthInterceptor} to the inbound channel. Spring adds each configurer's
 * interceptors in the configurers' order, and this configurer is ordered first, so a CONNECT is
 * authenticated before any other interceptor, such as the encounter subscription guard, sees a frame. It also wraps
 * the STOMP handler in {@link WebSocketSessionTracker}, so {@link WebSocketSessions} holds every open socket (D238).
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WebSocketAuthConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final WebSocketSessions webSocketSessions;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketSessionTracker(handler, webSocketSessions));
    }
}
