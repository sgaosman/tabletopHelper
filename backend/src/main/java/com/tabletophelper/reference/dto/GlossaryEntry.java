package com.tabletophelper.reference.dto;

import com.tabletophelper.reference.GlossaryCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One term of the Glossary (M19): a game term the descriptions use, such as a condition, an action or a spellcasting
 * word, with a short plain-English summary and the rules text it comes from. An entry has a summary, a body or both.
 *
 * @param id       a stable, lower-case key, such as {@code spell-save-dc}
 * @param aliases  other ways the term is written, in lower case, such as plurals
 * @param summary  a sentence or two for someone new to the game; null where the rules text is short enough
 * @param body     the rules text from the 5etools data, tags removed, one paragraph per line; null for a term the
 *                 data has no entry for
 * @param source   the book and page, such as {@code PHB p.290}
 * @param seeAlso  the IDs of related entries
 */
public record GlossaryEntry(
        @Schema(requiredMode = REQUIRED) String id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) GlossaryCategory category,
        @Schema(requiredMode = REQUIRED) List<String> aliases,
        String summary,
        String body,
        @Schema(requiredMode = REQUIRED) String source,
        @Schema(requiredMode = REQUIRED) List<String> seeAlso) {
}
