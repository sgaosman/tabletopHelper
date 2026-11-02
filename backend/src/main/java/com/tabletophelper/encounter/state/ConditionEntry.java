package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One condition on a participant, stored in {@code encounter_participants.active_conditions} (D168).
 *
 * @param id                          identifies this entry, so Legendary Resistance can remove exactly the
 *                                    conditions a save applied (D163)
 * @param name                        the condition, such as "prone"
 * @param duration                    how many rounds it lasts; null when it lasts until removed
 * @param appliedRound                the round it was applied in
 * @param sourceSpellName             the spell that applied it; for a monster's action or spell, the monster's
 *                                    display name
 * @param sourceParticipantId         the participant whose spell or action applied it
 * @param sourceRequiresConcentration true when it ends as soon as its caster loses concentration
 * @param concentrationInstanceId     the Concentration Instance that created it, which removes it on ending
 *                                    (D162); null for any other condition
 * @param endRule                     how it ends
 * @param repeatSave                  the save its creature repeats at the end of each of its turns to end it;
 *                                    null when there is none (D162)
 */
public record ConditionEntry(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) String name,
        Integer duration,
        @Schema(requiredMode = REQUIRED) int appliedRound,
        String sourceSpellName,
        UUID sourceParticipantId,
        Boolean sourceRequiresConcentration,
        UUID concentrationInstanceId,
        @Schema(requiredMode = REQUIRED) EndRule endRule,
        RepeatSave repeatSave
) {

    /**
     * A condition with no source, added by hand: it ends when removed, or after its duration at the start of
     * its creature's turn.
     */
    public static ConditionEntry manual(String name, Integer duration, int appliedRound) {
        return new ConditionEntry(UUID.randomUUID(), name, duration, appliedRound, null, null, null, null,
                duration != null ? EndRule.afterRounds(null) : EndRule.MANUAL, null);
    }

    /** The unconsciousness of a PC dropping to 0 HP, which ends when it regains hit points (D161). */
    public static ConditionEntry unconsciousAtZeroHp(int appliedRound) {
        return new ConditionEntry(UUID.randomUUID(), "unconscious", null, appliedRound, null, null, null, null,
                EndRule.HP_REGAINED, null);
    }

    /**
     * A condition an Effect applied, without a Repeat Save; see
     * {@link #fromEffect(String, Integer, int, String, UUID, ConcentrationInstance, RepeatSave)}.
     */
    public static ConditionEntry fromEffect(String name, Integer duration, int appliedRound, String sourceName,
                                            UUID sourceParticipantId, ConcentrationInstance instance) {
        return fromEffect(name, duration, appliedRound, sourceName, sourceParticipantId, instance, null);
    }

    /**
     * A condition an Effect applied. One created by a Concentration Instance ends with it (D162); any other
     * ends on its Repeat Save, after its duration at the start of its caster's turn, or when removed.
     */
    public static ConditionEntry fromEffect(String name, Integer duration, int appliedRound, String sourceName,
                                            UUID sourceParticipantId, ConcentrationInstance instance,
                                            RepeatSave repeatSave) {
        EndRule endRule;
        if (instance != null) {
            endRule = EndRule.CONCENTRATION;
        } else if (repeatSave != null) {
            endRule = EndRule.REPEAT_SAVE;
        } else if (duration != null) {
            endRule = EndRule.afterRounds(sourceParticipantId);
        } else {
            endRule = EndRule.MANUAL;
        }
        return new ConditionEntry(UUID.randomUUID(), name, duration, appliedRound, sourceName, sourceParticipantId,
                instance != null, instance != null ? instance.id() : null, endRule, repeatSave);
    }

    public ConditionEntry withRepeatSave(RepeatSave save) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, save);
    }
}
