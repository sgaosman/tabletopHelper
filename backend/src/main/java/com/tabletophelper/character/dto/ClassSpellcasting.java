package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What one of a character's classes casts with, worked out by the server (D158, D167): a Spellcasting Class, an
 * Eldritch Knight's or Arcane Trickster's subclass from 3rd level, or a warlock's Pact Magic. The numbers come from
 * the class's own tables (rules.character.ClassProgression) and its spellcasting ability, so a multiclass character
 * has a save DC and attack bonus for each class. Class spells in combat use them (D164).
 *
 * @param className         the class, as its class entry names it
 * @param subclassName      the class's subclass, which for a fighter or rogue is the one that gives it its spells
 * @param classLevel        the character's level in the class
 * @param ability           the class's spellcasting ability, abbreviated, such as "INT"
 * @param spellSaveDc       8 + the proficiency bonus + the ability's modifier (PHB p.205)
 * @param spellAttackBonus  the proficiency bonus + the ability's modifier
 * @param highestSpellLevel the highest level of spell the class can learn or prepare, from its own table (PHB p.164);
 *                          0 while it has no slots
 * @param cantripsKnown     cantrips the class knows; 0 for a class without them
 * @param spellsKnown       spells the class knows, for a class that knows a set number; null for one that prepares
 * @param preparedSpells    spells the class can prepare each day, 0 while it has no slots; null for one that knows
 *                          its spells
 * @param pactMagic         true for a warlock, whose slots are pact slots
 */
public record ClassSpellcasting(
        @Schema(requiredMode = REQUIRED) String className,
        String subclassName,
        @Schema(requiredMode = REQUIRED) int classLevel,
        @Schema(requiredMode = REQUIRED) String ability,
        @Schema(requiredMode = REQUIRED) int spellSaveDc,
        @Schema(requiredMode = REQUIRED) int spellAttackBonus,
        @Schema(requiredMode = REQUIRED) int highestSpellLevel,
        @Schema(requiredMode = REQUIRED) int cantripsKnown,
        Integer spellsKnown,
        Integer preparedSpells,
        @Schema(requiredMode = REQUIRED) boolean pactMagic
) {
}
