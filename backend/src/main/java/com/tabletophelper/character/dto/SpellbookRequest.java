package com.tabletophelper.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Spells to copy into a wizard's spellbook, or to take out of it (D158). A spell copied in must be a wizard spell of
 * 1st level or higher that the wizard can cast; one taken out must be in the book.
 */
public record SpellbookRequest(@NotEmpty List<@NotBlank String> spells) {
}
