package com.tabletophelper.content.internal;

import com.tabletophelper.rules.combat.SummonSpirits;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads {@code data/content/summon-spirits.json} into {@link SummonSpirits} (D182), as {@link ClassTablesFile} reads
 * the class tables: strictly, so a property the records don't have or a missing number fails, naming the file. A
 * spirit's action templates are kept as they are written.
 */
public final class SummonSpiritsFile {

    /** The file, on the classpath. */
    public static final String PATH = "data/content/summon-spirits.json";

    private SummonSpiritsFile() {
    }

    /** Reads the bundled file. */
    public static SummonSpirits read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return read(objectMapper, in);
        } catch (IOException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Reads the spirits from a stream holding the file's JSON. */
    public static SummonSpirits read(ObjectMapper objectMapper, InputStream in) {
        try {
            return objectMapper.readerFor(SummonSpirits.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(in);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
    }
}
