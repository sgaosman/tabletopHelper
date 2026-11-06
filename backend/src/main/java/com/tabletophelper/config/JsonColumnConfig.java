package com.tabletophelper.config;

import tools.jackson.databind.json.JsonMapper;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.tabletophelper.shared.JacksonDefaults;

/**
 * Typed JSON columns (D168) are read and written with the application's JsonMapper, so a column and
 * the API read the same JSON the same way. Hibernate's own mapper would fail a whole encounter's load on
 * one unknown property, such as an extra key in a spell copied from a character sheet. The mapper keeps
 * the Jackson 2 behaviour the stored rows were written with (JacksonDefaults, D154).
 */
@Configuration(proxyBeanMethods = false)
public class JsonColumnConfig {

    // After Spring Boot's own customizer, so these settings are the ones that hold
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    JsonMapperBuilderCustomizer jacksonDefaults() {
        return JacksonDefaults::apply;
    }

    @Bean
    HibernatePropertiesCustomizer jsonFormatMapper(JsonMapper jsonMapper) {
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new Jackson3JsonFormatMapper(jsonMapper));
    }
}
