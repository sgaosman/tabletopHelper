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
 * @param spellList         the spell list the class learns and prepares from, as the spells table names it: the
 *                          class's own, or "Wizard" for an Eldritch Knight or Arcane Trickster (PHB p.75, p.98)
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
 * @param subclassSpellList the name the spells table gives the subclass's additions to the list, such as "Warlock (The
 *                          Fathomless)", or for a subclass whose additions follow its player's option, the option's,
 *                          such as "Warlock (The Genie: Dao)" or "Druid (Circle of the Land: Arctic)" (D240); null
 *                          without a subclass. A subclass whose option isn't picked yet has its name alone, which no
 *                          spell is listed under
 */
public record ClassSpellcasting(
        @Schema(requiredMode = REQUIRED) String className,
        String subclassName,
        @Schema(requiredMode = REQUIRED) String spellList,
        @Schema(requiredMode = REQUIRED) int classLevel,
        @Schema(requiredMode = REQUIRED) String ability,
        @Schema(requiredMode = REQUIRED) int spellSaveDc,
        @Schema(requiredMode = REQUIRED) int spellAttackBonus,
        @Schema(requiredMode = REQUIRED) int highestSpellLevel,
        @Schema(requiredMode = REQUIRED) int cantripsKnown,
        Integer spellsKnown,
        Integer preparedSpells,
        @Schema(requiredMode = REQUIRED) boolean pactMagic,
        String subclassSpellList
) {

    /** A class's numbers whose subclass's additions are named by the class and subclass alone. */
    public ClassSpellcasting(String className, String subclassName, String spellList, int classLevel, String ability,
                             int spellSaveDc, int spellAttackBonus, int highestSpellLevel, int cantripsKnown,
                             Integer spellsKnown, Integer preparedSpells, boolean pactMagic) {
        this(className, subclassName, spellList, classLevel, ability, spellSaveDc, spellAttackBonus, highestSpellLevel,
                cantripsKnown, spellsKnown, preparedSpells, pactMagic,
                subclassName != null && !subclassName.isBlank() ? className + " (" + subclassName + ")" : null);
    }
}
