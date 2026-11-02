package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One condition on a participant, stored in {@code encounter_participants.active_conditions} (D168).
 *
 * @param name                        the condition, such as "prone"
 * @param duration                    how many rounds it lasts; null when it lasts until removed
 * @param appliedRound                the round it was applied in
 * @param sourceSpellName             the spell that applied it; for a monster's action or spell, the monster's
 *                                    display name
 * @param sourceParticipantId         the participant whose spell or action applied it
 * @param sourceRequiresConcentration true when it ends as soon as its caster loses concentration
 */
public record ConditionEntry(
        @Schema(requiredMode = REQUIRED) String name,
        Integer duration,
        @Schema(requiredMode = REQUIRED) int appliedRound,
        String sourceSpellName,
        UUID sourceParticipantId,
        Boolean sourceRequiresConcentration
) {

    /** A condition with no source: added by hand, or by dropping to 0 HP and being revived. */
    public static ConditionEntry unsourced(String name, Integer duration, int appliedRound) {
        return new ConditionEntry(name, duration, appliedRound, null, null, null);
    }
}
