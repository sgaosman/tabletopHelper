package com.tabletophelper.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * The OpenAPI spec the frontend generates its API types from (D171). The prod profile turns it off
 * (D154).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** A relative server URL, so the spec doesn't depend on the host or port that served it. */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().servers(List.of(new Server().url("/")));
    }

    /**
     * Jackson writes null fields, so every property is nullable unless its DTO marks it
     * {@code @Schema(requiredMode = REQUIRED)}.
     */
    @Bean
    OpenApiCustomizer nullableUnlessRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::markOptionalPropertiesNullable);
        };
    }

    @SuppressWarnings("rawtypes")
    private static void markOptionalPropertiesNullable(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
        properties.replaceAll((name, property) -> required.contains(name) ? property : nullable(property));
    }

    // OpenAPI 3.0 ignores every keyword beside a $ref, so a nullable reference is wrapped in allOf
    @SuppressWarnings("rawtypes")
    private static Schema nullable(Schema property) {
        if (property.get$ref() == null) {
            return property.nullable(true);
        }
        return new Schema<>()
                .addAllOfItem(new Schema<>().$ref(property.get$ref()))
                .description(property.getDescription())
                .nullable(true);
    }
}
