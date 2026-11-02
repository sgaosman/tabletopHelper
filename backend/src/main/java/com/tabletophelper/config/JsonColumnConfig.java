package com.tabletophelper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Typed JSON columns (D168) are read and written with the application's ObjectMapper, so a column and
 * the API read the same JSON the same way. Hibernate's own mapper would fail a whole encounter's load on
 * one unknown property, such as an extra key in a spell copied from a character sheet.
 */
@Configuration(proxyBeanMethods = false)
public class JsonColumnConfig {

    @Bean
    HibernatePropertiesCustomizer jsonFormatMapper(ObjectMapper objectMapper) {
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
