package com.tabletophelper.content;

import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.GlossaryFile;
import com.tabletophelper.reference.Glossary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Glossary (M19): {@code content} builds it from {@code data/content/glossary-terms.json} and the 5etools files
 * its terms point to, once, as the application starts, as {@link ClassTablesConfig} does for the class tables. It
 * isn't stored, so it needs no migration or content version, and a term whose rules text can't be found stops the
 * start (D155).
 */
@Configuration
@Slf4j
public class GlossaryConfig {

    @Bean
    public Glossary glossary(ObjectMapper objectMapper) {
        Glossary glossary = GlossaryFile.read(objectMapper);
        log.info("Read {} glossary terms from {}", glossary.entries().size(), GlossaryFile.PATH);
        return glossary;
    }
}
