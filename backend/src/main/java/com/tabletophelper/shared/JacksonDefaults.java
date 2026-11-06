package com.tabletophelper.shared;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Jackson settings every mapper in the application uses: Spring's, and so the API, the STOMP messages and the
 * JSON columns, and the few that records build for themselves. Jackson 3 changed several defaults, and each one is
 * set back here to what the application had on Jackson 2 with Spring Boot 3, so stored JSON and the API read and
 * write exactly as before (D154).
 */
public final class JacksonDefaults {

    private JacksonDefaults() {
    }

    /** The builder with the settings applied. */
    public static JsonMapper.Builder apply(JsonMapper.Builder builder) {
        return builder
                // Properties in declaration order, not alphabetical: the API's field order stays as it was
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // A JSON null or a missing value for an int or boolean field reads as 0 or false; stored
                // rows written before a field existed, or with nulls in it, still load
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // Content after the first value is ignored, as before
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // Unknown keys are skipped (Spring Boot 3 set this; Jackson 3's default agrees)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Enums by name(), not toString()
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                // Instants and durations as ISO-8601 text (Spring Boot 3 set these; Jackson 3's defaults agree)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DateTimeFeature.ONE_BASED_MONTHS)
                // An object with nothing to write is an error rather than {}
                .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                // Properties without a @JsonView are left out of a view (Spring Boot 3 set this)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                // Bean property discovery as on Jackson 2
                .enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
                .enable(MapperFeature.USE_GETTERS_AS_SETTERS)
                .disable(MapperFeature.FIX_FIELD_NAME_UPPER_CASE_PREFIX)
                .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    /** A new builder with the settings applied. */
    public static JsonMapper.Builder builder() {
        return apply(JsonMapper.builder());
    }

    /** A new mapper with the settings applied, for code that has no Spring context, such as unit tests. */
    public static JsonMapper mapper() {
        return builder().build();
    }
}
