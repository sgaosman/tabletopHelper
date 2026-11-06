package com.tabletophelper.content.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.rules.character.ClassTables;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads {@code data/content/class-tables.json} into {@link ClassTables} (D167, D169). The file is read strictly: a
 * property the tables don't have, a missing number or a column of the wrong length fails, naming the file.
 */
public final class ClassTablesFile {

    /** The file, on the classpath. */
    public static final String PATH = "data/content/class-tables.json";

    private ClassTablesFile() {
    }

    /** Reads the bundled file. */
    public static ClassTables read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return read(objectMapper, in);
        } catch (IOException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Reads the tables from a stream holding the file's JSON. */
    public static ClassTables read(ObjectMapper objectMapper, InputStream in) {
        try {
            return objectMapper.readerFor(ClassTables.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(PATH + " could not be read: " + e.getMessage(), e);
        }
    }
}
