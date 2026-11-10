package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.rules.combat.OngoingModifier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
 * @param delayed                 damage still to come to creatures it reached, as Vitriolic Sphere's 5d4 acid at the
 *                                end of a creature's next turn after a failed save (XGE p.170, D185); empty, and left
 *                                out of the JSON, for none
 * @param damageLeft              the damage it can still deal before it ends, as Guardian of Faith's 60 (PHB p.246,
 *                                D185); null for no limit
 * @param retaliation             the damage it deals to a creature that hits its caster with a melee attack, as Fire
 *                                Shield's (PHB p.242, D185); null for none
 * @param exhaustion              the exhaustion levels it gave, which go when it ends, as Sickening Radiance's (XGE
 *                                p.164, D185); empty, and left out of the JSON, for none
 * @param turnReminder            what the log reminds its caster of at the start of each of their turns, as
 *                                Mordenkainen's Faithful Hound's bite (PHB p.261, D185); null for nothing
 */
public record SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                               int startedRound, Integer durationRounds, Integer usesLeft,
                               @JsonInclude(JsonInclude.Include.NON_NULL) SummonSpec summon,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> affected,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<OngoingModifier> modifiers,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String option,
                               @JsonInclude(JsonInclude.Include.NON_NULL) EffectSave repeatSave,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String endReminder,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Boolean modifiersOnly,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DelayedDamage> delayed,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Integer damageLeft,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Retaliation retaliation,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ExhaustionGiven> exhaustion,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String turnReminder) {

    /** A saving throw by ability and DC. */
    public record EffectSave(String ability, int dc) {
    }

    /**
     * Damage a creature takes later from the spell (D185).
     *
     * @param target the creature
     * @param dice   the dice, fixed when cast, such as "5d4"
     * @param type   the damage type
     * @param armed  its creature's next turn has started, so that turn's end brings the damage
     */
    public record DelayedDamage(@Schema(requiredMode = REQUIRED) UUID target, @Schema(requiredMode = REQUIRED) String dice,
                                @Schema(requiredMode = REQUIRED) String type, @Schema(requiredMode = REQUIRED) boolean armed) {

        public DelayedDamage armedNow() {
            return new DelayedDamage(target, dice, type, true);
        }
    }

    /** The damage a protective effect deals back to a creature that hits its caster in melee (D185). */
    public record Retaliation(String dice, String type) {
    }

    /** Exhaustion levels the effect gave a creature, which go when it ends (D185). */
    public record ExhaustionGiven(UUID creature, int levels) {
    }

    public SpellEffectEntry {
        affected = affected != null ? List.copyOf(affected) : List.of();
        modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
        delayed = delayed != null ? List.copyOf(delayed) : List.of();
        exhaustion = exhaustion != null ? List.copyOf(exhaustion) : List.of();
    }

    /** An effect without the 4th-level spells' additions (D185). */
    public SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                            int startedRound, Integer durationRounds, Integer usesLeft, SummonSpec summon,
                            List<UUID> affected, List<OngoingModifier> modifiers, String option, EffectSave repeatSave,
                            String endReminder, Boolean modifiersOnly) {
        this(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, usesLeft, summon, affected,
                modifiers, option, repeatSave, endReminder, modifiersOnly, null, null, null, null, null);
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
                summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect with this summoning. */
    public SpellEffectEntry withSummon(SummonSpec spec) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, spec, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /**
     * The same effect with Ongoing Modifiers (D184) on these creatures.
     *
     * @param only true when the effect exists only for them, so it ends with the last of them
     */
    public SpellEffectEntry withModifiers(List<UUID> creatures, List<OngoingModifier> changes, String chosen,
                                          EffectSave save, String reminder, boolean only) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, creatures, changes, chosen, save, reminder, only ? Boolean.TRUE : null, delayed, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect on these creatures. */
    public SpellEffectEntry withAffected(List<UUID> creatures) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, creatures, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect with these modifiers left. */
    public SpellEffectEntry withModifiersLeft(List<OngoingModifier> left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, left, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect with this damage still to come (D185). */
    public SpellEffectEntry withDelayed(List<DelayedDamage> left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, left, damageLeft,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect with this much damage left to deal (D185). */
    public SpellEffectEntry withDamageLeft(Integer left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, left,
                retaliation, exhaustion, turnReminder);
    }

    /** The same effect dealing this back to a creature that hits its caster in melee (D185). */
    public SpellEffectEntry withRetaliation(Retaliation dealt) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, dealt, exhaustion, turnReminder);
    }

    /** The same effect having given these exhaustion levels (D185). */
    public SpellEffectEntry withExhaustion(List<ExhaustionGiven> given) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, given, turnReminder);
    }

    /** The same effect with this reminder at the start of its caster's turns (D185). */
    public SpellEffectEntry withTurnReminder(String reminder) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, reminder);
    }

    /**
     * Whether it exists only for damage still to come, as an instantaneous spell's delayed damage does (D185), so it ends
     * once that is dealt.
     */
    public boolean onlyForDelayed() {
        return concentrationInstanceId == null && summon == null && modifiers.isEmpty() && usesLeft == null
                && damageLeft == null && retaliation == null && turnReminder == null && exhaustion.isEmpty();
    }

    /** Whether it exists only for its Ongoing Modifiers, so it ends with the last of them. */
    public boolean onlyForModifiers() {
        return Boolean.TRUE.equals(modifiersOnly);
    }
}
