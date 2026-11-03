package com.tabletophelper.character.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * What one of a character's classes knows (D158): its cantrips and, for a class that knows a set number of spells,
 * its spells. Each list given replaces what the class knows of that kind, and a list left out keeps it. Every spell
 * must be on the class's spell list and of a level the class can cast, within the numbers the class knows. This
 * interim command lasts until workstream F's Pending Choices replace it (D159).
 *
 * @param className the class, as its class entry names it
 * @param cantrips  the cantrips the class knows, or null to keep them
 * @param spells    the levelled spells a class that knows its spells knows, or null to keep them
 */
public record KnownSpellsRequest(
        @NotBlank String className,
        List<@NotBlank String> cantrips,
        List<@NotBlank String> spells
) {
}
