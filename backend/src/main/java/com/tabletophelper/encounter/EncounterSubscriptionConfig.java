package com.tabletophelper.encounter;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Adds {@link EncounterSubscriptionGuard} to the inbound channel beside the CONNECT check that
 * {@code WebSocketConfig} registers. Spring combines the interceptors from every configurer, and
 * the two check different frames, so their order doesn't matter.
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
