package com.tabletophelper.encounter.internal;

import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.Resolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Combat's dice and the resolver that rolls them (D160). */
@Configuration
public class CombatConfig {

    /** Random dice in play; the integration tests put scripted dice in its place. */
    @Bean
    public Rng rng() {
        return Rng.random();
    }

    @Bean
    public Resolver resolver(Rng rng) {
        return new Resolver(rng);
    }
}
