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
 * @param source          where it comes from, such as "class:Wizard", "race:Tiefling", "feat:Magic Initiate"
 *                        or "monster"
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

    /** A spell read from a monster's stat block. */
    public static KnownSpell monster(UUID id, String name, int level) {
        return new KnownSpell(id, name, level, "monster", null, null, null, null, null);
    }
}
