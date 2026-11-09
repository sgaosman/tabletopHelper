package com.tabletophelper.reference;

import com.tabletophelper.reference.dto.GlossaryEntry;

import java.util.List;

/**
 * The Glossary (M19): the game terms the descriptions use, in the order of {@code data/content/glossary-terms.json}.
 * {@code content} builds it once at startup from that file and the 5etools data it points to; it is never stored.
 */
public record Glossary(List<GlossaryEntry> entries) {

    public Glossary {
        entries = List.copyOf(entries);
    }
}
