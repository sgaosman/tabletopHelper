package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A spell a character or participant can cast (D168), stored in {@code player_characters.spells_known} and
 * {@code encounter_participants.spells_known}. A player character's entries are copied from the sheet when they
 * join an encounter; a monster's come from its stat block. A field an entry doesn't have is left out.
 *
 * @param id              the spell's ID; only monster entries have one, and not when the reference data
 *                        lacks the spell
 * @param name            the spell's name
 * @param level           the spell's level, 0 for a cantrip
 * @param source          where it comes from, such as "class:Wizard", "race:Tiefling", "feat:Magic Initiate",
 *                        "subclass:Arcane Archer", "arcanum:Warlock" for a Mystic Arcanum, or "monster"
 * @param prepared        true when a prepared caster has it prepared
 * @param alwaysPrepared  true when it is always prepared
 * @param atWill          true when it is cast without a slot
 * @param usesPerLongRest how often it can be cast without a slot between long rests
 * @param unlocksAtLevel  the character level a racial spell becomes available at
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnownSpell(
        UUID id,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) int level,
        String source,
        Boolean prepared,
        Boolean alwaysPrepared,
        Boolean atWill,
        Integer usesPerLongRest,
        Integer unlocksAtLevel
) {

    /**
     * What the source of a cantrip a subclass taught starts with, before the subclass's name, such as "subclass:Nature
     * Domain" for a Nature Domain cleric's druid cantrip (PHB p.61); it doesn't count against a class's cantrips known.
     */
    public static final String SUBCLASS_SOURCE = "subclass:";

    /**
     * What the source of a warlock's Mystic Arcanum spell starts with, before the class's name: "arcanum:Warlock" (PHB
     * p.108). It is cast once without a slot between long rests, from its pool, uses the warlock's save DC and attack
     * bonus, and doesn't count against the warlock's spells known.
     */
    public static final String MYSTIC_ARCANUM_SOURCE = "arcanum:";

    /** Whether the entry is a Mystic Arcanum spell, by its source. */
    public static boolean isMysticArcanum(KnownSpell spell) {
        return spell != null && spell.source() != null && spell.source().startsWith(MYSTIC_ARCANUM_SOURCE);
    }

    /** A spell read from a monster's stat block. */
    public static KnownSpell monster(UUID id, String name, int level) {
        return new KnownSpell(id, name, level, "monster", null, null, null, null, null);
    }
}
