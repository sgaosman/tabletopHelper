package com.tabletophelper.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Spells to copy into a wizard's spellbook, or to take out of it (D158). A spell copied in must be a wizard spell of
 * 1st level or higher that the wizard can cast; one taken out must be in the book.
 */
public record SpellbookRequest(@NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 200) String> spells) {
}
