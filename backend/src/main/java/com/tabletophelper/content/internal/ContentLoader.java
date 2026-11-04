package com.tabletophelper.content.internal;

import java.util.List;

/**
 * Loads one kind of game content from bundled data files into its tables (D169). A loader updates the rows it finds
 * by natural key in place, keeping their IDs, inserts the rest and deletes nothing: a row missing from the data is
 * kept and logged. {@code ContentLoading} runs it again only when the hash of its files and {@link #version()}
 * changes, or when a loader it depends on ran.
 */
public interface ContentLoader {

    /** The loader's name, as {@code content_versions} stores it. */
    String name();

    /** The classpath patterns of the files it reads, such as {@code data/5etools/spells/spells-*.json}. */
    List<String> files();

    /**
     * The version of the loader's code. Raise it when a change to the code changes what it writes from the same
     * files, so that it runs again.
     */
    int version();

    /** The loaders whose rows this one reads, which run before it. */
    default List<String> dependsOn() {
        return List.of();
    }

    /** Reads the files and writes the rows, inside the caller's transaction. */
    void load(LoadContext context) throws Exception;
}
