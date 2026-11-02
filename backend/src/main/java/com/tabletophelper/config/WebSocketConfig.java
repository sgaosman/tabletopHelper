package com.tabletophelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The STOMP broker and the /ws endpoint. Each module registers its own inbound interceptors: identity's
 * {@code WebSocketAuthConfig} checks CONNECT first, then encounter's {@code EncounterSubscriptionConfig}
 * checks SUBSCRIBE and SEND frames (D151, D157).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value(CorsConfig.ALLOWED_ORIGINS)
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The origins HTTP CORS allows. A page on the server's own origin may always connect, so an
        // empty CORS_ALLOWED_ORIGINS means same origin only (D157)
        registry.addEndpoint("/ws")
                .setAllowedOrigins(CorsConfig.parseOrigins(allowedOrigins).toArray(String[]::new))
                .withSockJS();
    }
}
