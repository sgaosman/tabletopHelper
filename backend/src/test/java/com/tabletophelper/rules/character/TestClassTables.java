package com.tabletophelper.rules.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.content.internal.ClassTablesFile;

/** The class tables as the app reads them, from data/content/class-tables.json on the classpath, read once. */
public final class TestClassTables {

    private static final ClassTables TABLES = ClassTablesFile.read(new ObjectMapper());

    private TestClassTables() {
    }

    public static ClassTables load() {
        return TABLES;
    }
}
