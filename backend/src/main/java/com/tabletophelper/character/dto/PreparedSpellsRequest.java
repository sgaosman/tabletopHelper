package com.tabletophelper.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The spells one of a character's classes has prepared, which replace those it had prepared (D158). Each must be on
 * the class's spell list, or for a wizard in its spellbook, and of a level the class can cast, and there can be no
 * more than the class prepares. Always-prepared spells don't count, and stay prepared.
 *
 * @param className the class, as its class entry names it
 */
public record PreparedSpellsRequest(
        @NotBlank String className,
        @NotNull List<@NotBlank String> spells
) {
}
