package com.tabletophelper.encounter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Adds {@link EncounterSubscriptionGuard} to the inbound channel. Spring combines the interceptors
 * from every configurer in the configurers' order; identity's CONNECT check is ordered first, so it
 * runs before this guard.
 */
@Configuration
@RequiredArgsConstructor
public class EncounterSubscriptionConfig implements WebSocketMessageBrokerConfigurer {

    private final EncounterSubscriptionGuard encounterSubscriptionGuard;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(encounterSubscriptionGuard);
    }
}
