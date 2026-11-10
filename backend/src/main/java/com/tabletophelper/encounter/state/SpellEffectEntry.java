package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.rules.combat.OngoingModifier;

import java.util.List;
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
 * @param summon                  the creatures a summoning spell brings, which leave when the effect ends (D182, V34);
 *                                null for any other spell, and left out of the JSON then, so rows written before V34
 *                                read back as they are
 * @param affected                the creatures its Ongoing Modifiers apply to (D184): its targets, or its caster for a
 *                                spell on the caster; for a modifier scoped to the caster's attacks, such as Hunter's
 *                                Mark's, the creatures marked. Empty, and left out of the JSON, for none
 * @param modifiers               its Ongoing Modifiers (D184), their numbers fixed when it was cast; empty, and left
 *                                out of the JSON, for none
 * @param option                  the option chosen when it was cast, such as "reduce" or "fire"; null for none
 * @param repeatSave              the save each affected creature repeats at the end of each of its turns, ending the
 *                                modifiers for itself on a success, as Slow's (PHB p.277); null for none
 * @param endReminder             what the log reminds the table of when it ends, such as Haste's lethargy (PHB p.250);
 *                                null for nothing
 * @param modifiersOnly           true when it exists only for its modifiers, so it ends once the last of them is spent
 *                                or ends; null otherwise
 */
public record SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                               int startedRound, Integer durationRounds, Integer usesLeft,
                               @JsonInclude(JsonInclude.Include.NON_NULL) SummonSpec summon,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> affected,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<OngoingModifier> modifiers,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String option,
                               @JsonInclude(JsonInclude.Include.NON_NULL) EffectSave repeatSave,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String endReminder,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Boolean modifiersOnly) {

    /** A saving throw by ability and DC. */
    public record EffectSave(String ability, int dc) {
    }

    public SpellEffectEntry {
        affected = affected != null ? List.copyOf(affected) : List.of();
        modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
    }

    /** An effect with no Ongoing Modifiers (D184). */
    public SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                            int startedRound, Integer durationRounds, Integer usesLeft, SummonSpec summon) {
        this(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, usesLeft, summon, null, null,
                null, null, null, null);
    }

    /** An effect that summons nothing. */
    public SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                            int startedRound, Integer durationRounds, Integer usesLeft) {
        this(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, usesLeft, null);
    }

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
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, uses,
                summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly);
    }

    /** The same effect with this summoning. */
    public SpellEffectEntry withSummon(SummonSpec spec) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, spec, affected, modifiers, option, repeatSave, endReminder, modifiersOnly);
    }

    /**
     * The same effect with Ongoing Modifiers (D184) on these creatures.
     *
     * @param only true when the effect exists only for them, so it ends with the last of them
     */
    public SpellEffectEntry withModifiers(List<UUID> creatures, List<OngoingModifier> changes, String chosen,
                                          EffectSave save, String reminder, boolean only) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, creatures, changes, chosen, save, reminder, only ? Boolean.TRUE : null);
    }

    /** The same effect on these creatures. */
    public SpellEffectEntry withAffected(List<UUID> creatures) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, creatures, modifiers, option, repeatSave, endReminder, modifiersOnly);
    }

    /** The same effect with these modifiers left. */
    public SpellEffectEntry withModifiersLeft(List<OngoingModifier> left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, left, option, repeatSave, endReminder, modifiersOnly);
    }

    /** Whether it exists only for its Ongoing Modifiers, so it ends with the last of them. */
    public boolean onlyForModifiers() {
        return Boolean.TRUE.equals(modifiersOnly);
    }
}
