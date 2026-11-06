package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A Pending Choice (D159): a choice one of a character's levels owes that its player hasn't made yet, in
 * {@code player_characters.pending_choices}, oldest level first (V23). Making it records the answer in that level's
 * history entry, so levelling down reverses it, and takes it off the list; levelling down removes the level's own.
 * The player may leave it for later, and level up or join an encounter meanwhile. A field a type doesn't use is left
 * out.
 *
 * @param characterLevel the level that owes it, whose history entry records the answer: 1 for a race's or
 *                       background's feat
 * @param classId        the class whose level owes it; null for a race's or background's feat
 * @param className      the class, as its class entry names it
 * @param classLevel     the class level that owes it
 * @param count          how many to choose: skills for Expertise, cantrips, or spells; 1 for the others
 * @param options        the fixed list it is chosen from, where it has one: the subclasses, the Fighting Styles, or the
 *                       feats a background offers; null for any, as an Ability Score Improvement's feat is
 * @param maxSpellLevel  for CANTRIPS 0, and for SPELLS the highest level of spell the class could learn at that class
 *                       level, from its own table (PHB p.164)
 * @param swap           for SPELLS, true when the class may also replace one spell it knows with another, as a bard,
 *                       ranger, sorcerer, warlock, Eldritch Knight or Arcane Trickster may on gaining a level in the
 *                       class
 * @param spellList      for CANTRIPS and SPELLS, the spell list the class learns from, as the spells table names it:
 *                       the class's own, or "Wizard" for an Eldritch Knight or Arcane Trickster; null on a choice
 *                       made before R8, where it is the class's own unless the class is one of those two
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingChoice(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) Type type,
        @Schema(requiredMode = REQUIRED) Source source,
        @Schema(requiredMode = REQUIRED) int characterLevel,
        UUID classId,
        String className,
        Integer classLevel,
        @Schema(requiredMode = REQUIRED) int count,
        List<String> options,
        Integer maxSpellLevel,
        Boolean swap,
        String spellList
) {

    /** What is chosen, in the order a level's choices are shown and made: a subclass can bring more. */
    public enum Type {
        /** The class's subclass, which can bring more choices of its own, such as an Eldritch Knight's spells */
        SUBCLASS,
        /** A Fighting Style, from the class's list */
        FIGHTING_STYLE,
        /** An Ability Score Improvement: +2 to one score or +1 to two, or a feat in its place */
        ASI,
        /** A feat a race or background gives at creation, such as a Variant Human's */
        FEAT,
        /** Skills that gain Expertise */
        EXPERTISE,
        /** Cantrips the class learns */
        CANTRIPS,
        /**
         * Spells the class learns, with a known caster's optional swap of one it knows; for a wizard, spells copied
         * into its spellbook for free
         */
        SPELLS
    }

    /** Where it came from. */
    public enum Source {
        /** A level gained by levelling up, or a level the backfill-pending-choices job found it missing from */
        LEVEL_UP,
        /** One of the levels a character was created with */
        CREATION,
        /** The character's race, at creation */
        RACE,
        /** The character's background, at creation */
        BACKGROUND
    }

    /** The same choice with a new count, as when creation made part of it. */
    public PendingChoice withCount(int newCount) {
        return new PendingChoice(id, type, source, characterLevel, classId, className, classLevel, newCount, options,
                maxSpellLevel, swap, spellList);
    }
}
