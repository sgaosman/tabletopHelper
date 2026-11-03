package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The spells a level's SPELLS choice gave the class, as its level history entry records them (D159): the spells
 * learned, or for a wizard copied into its spellbook, and a known caster's swap of one spell it knew for another.
 * Levelling down takes the learned spells and the replacement away and gives back the spell replaced.
 *
 * @param learned     the spells the class learned, by name
 * @param replaced    the spell the class knew and replaced, as the sheet had it
 * @param replacement the spell it learned in the replaced spell's place
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpellsLearned(
        @Schema(requiredMode = REQUIRED) List<String> learned,
        KnownSpell replaced,
        String replacement
) {
}
