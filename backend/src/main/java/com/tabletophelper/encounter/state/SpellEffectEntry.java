package com.tabletophelper.encounter.state;

import java.util.UUID;

/**
 * A Spell Effect: one lasting effect of a spell its caster cast, stored in {@code encounter_participants.active_effects}
 * on the caster's row (M15, V33), such as Spiritual Weapon's floating weapon or Moonbeam's beam. It isn't a creature:
 * it has no hit points, AC or turn of its own. Its caster directs it on their own turn, at what its template says that
 * costs, and the DM applies what it does to creatures on their turns (entering it, starting or ending a turn there).
 *
 * @param id                      identifies the effect, so a command can name it
 * @param spell                   the spell's name
 * @param slotLevel               the slot level it was cast with; null when unknown, as for concentration set by hand
 * @param concentrationInstanceId the Concentration Instance it belongs to, which ends it when it ends (D162); null
 *                                for a spell that needs no concentration, such as Spiritual Weapon
 * @param startedRound            the round it was cast in
 * @param durationRounds          how many rounds it lasts; null when unknown. It ends at the start of its caster's turn
 *                                once they have passed, as a condition's duration does (D162)
 * @param usesLeft                what it has left to spend, such as Melf's Minute Meteors' meteors; null when it has
 *                                no count
 */
public record SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                               int startedRound, Integer durationRounds, Integer usesLeft) {

    /** A new effect, with an ID of its own. */
    public static SpellEffectEntry start(String spell, Integer slotLevel, UUID concentrationInstanceId,
                                         int startedRound, Integer durationRounds, Integer usesLeft) {
        return new SpellEffectEntry(UUID.randomUUID(), spell, slotLevel, concentrationInstanceId, startedRound,
                durationRounds, usesLeft);
    }

    /** The slot level it resolves at: the one it was cast with, or 0 when unknown, which adds no upcast dice. */
    public int castLevel() {
        return slotLevel != null ? slotLevel : 0;
    }

    /** Whether its duration has run out by the start of its caster's turn in this round. */
    public boolean expiredBy(int round) {
        return durationRounds != null && round - startedRound >= durationRounds;
    }

    /** The same effect with this many uses left. */
    public SpellEffectEntry withUsesLeft(Integer uses) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, uses);
    }
}
