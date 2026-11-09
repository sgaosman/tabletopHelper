package com.tabletophelper.content;

import com.tabletophelper.content.internal.SummonSpiritsFile;
import com.tabletophelper.rules.combat.SummonSpirits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The spirits of Tasha's summoning spells (D182): {@code content} reads {@code data/content/summon-spirits.json} into
 * {@link SummonSpirits} once, as the application starts, as {@link ClassTablesConfig} reads the class tables, and the
 * encounter scales them at each cast. A file that can't be read stops the start.
 */
@Configuration
@Slf4j
public class SummonSpiritsConfig {

    @Bean
    public SummonSpirits summonSpirits(ObjectMapper objectMapper) {
        SummonSpirits spirits = SummonSpiritsFile.read(objectMapper);
        log.info("Read {} summoned spirits from {}", spirits.spirits().size(), SummonSpiritsFile.PATH);
        return spirits;
    }
}
