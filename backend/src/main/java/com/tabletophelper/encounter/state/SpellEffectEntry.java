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
 * @param countedSave             the saves its creatures count at the end of their turns, as Contagion's (PHB p.227,
 *                                D187); null for none
 * @param controlled              the creatures it put under its caster's control, as Dominate Person's (PHB p.235,
 *                                D187), which go back when it ends; empty, and left out of the JSON, for none
 * @param detonation              the damage it deals when it ends, however it ends, to the creatures in {@code affected},
 *                                as Delayed Blast Fireball's bead (PHB p.230, D189); null for none
 * @param rising                  the creatures it killed that rise at the start of its caster's next turn, as Finger
 *                                of Death's humanoids rising as zombies (PHB p.241, D189); empty, and left out of the
 *                                JSON, for none
 * @param rider                   what a weapon rider or a creature's own turn needs from its cast later, when its caster
 *                                rolls nothing, as Wrathful Smite's save on the hit (PHB p.289) and Searing Smite's
 *                                burning at the start of the target's turns (PHB p.274) (D199); null, and left out of
 *                                the JSON, for none
 * @param monsterRider            what a monster's hit left on a creature, when the effect is that, as a wight's lowered
 *                                hit point maximum (D203); null, and left out of the JSON, otherwise
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
                               @JsonInclude(JsonInclude.Include.NON_NULL) String turnReminder,
                               @JsonInclude(JsonInclude.Include.NON_NULL) CountedSave countedSave,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Controlled> controlled,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Detonation detonation,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> rising,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Rider rider,
                               @JsonInclude(JsonInclude.Include.NON_NULL) MonsterRider monsterRider) {

    /** An effect no monster's hit left (D203). */
    public SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId, int startedRound,
                            Integer durationRounds, Integer usesLeft, SummonSpec summon, List<UUID> affected,
                            List<OngoingModifier> modifiers, String option, EffectSave repeatSave, String endReminder,
                            Boolean modifiersOnly, List<DelayedDamage> delayed, Integer damageLeft,
                            Retaliation retaliation, List<ExhaustionGiven> exhaustion, String turnReminder,
                            CountedSave countedSave, List<Controlled> controlled, Detonation detonation,
                            List<UUID> rising, Rider rider) {
        this(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, usesLeft, summon, affected,
                modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft, retaliation, exhaustion,
                turnReminder, countedSave, controlled, detonation, rising, rider, null);
    }

    /**
     * What a monster's hit left on a creature, held by the monster as a Spell Effect named for its action, such as
     * "Wight's Life Drain" (D203), so it lasts as the spells' effects do and its Ongoing Modifiers count as theirs.
     *
     * @param kind       HP_MAX (a lowered hit point maximum), MODIFIER (Ongoing Modifiers), TURN_DAMAGE (damage at the
     *                   start of a turn), IMMUNITY (the creature is immune to the action once its save succeeded or
     *                   the anchor's condition ended), SWALLOW (the creatures in the monster's gullet, D204), COUNTED
     *                   (saves the creature counts, as a cockatrice's petrification, D204), ABILITY (a lowered ability
     *                   score, D204), POSSESSION (the body a ghost possesses, D204) or RISING (a creature it killed
     *                   that rises, D204)
     * @param action     the action's name, as the stat block gives it
     * @param anchor     the creature's condition entry it lasts while, ending with it, as a bearded devil's poisoning
     *                   while it can't regain hit points (MM p.70); null for none
     * @param anchorName that condition's name, for the log
     * @param rest       LONG_REST or SHORT_REST when it lasts until the creature finishes a rest, which the encounter
     *                   doesn't reach, so the table carries it over after Write-back; null otherwise
     * @param turnDamage for TURN_DAMAGE, what it deals; null otherwise
     * @param swallow    for SWALLOW, what the gullet does and the damage it has taken from inside this turn; null
     *                   otherwise
     */
    public record MonsterRider(@Schema(requiredMode = REQUIRED) String kind,
                               @Schema(requiredMode = REQUIRED) String action,
                               @JsonInclude(JsonInclude.Include.NON_NULL) UUID anchor,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String anchorName,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String rest,
                               @JsonInclude(JsonInclude.Include.NON_NULL) TurnDamage turnDamage,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Gullet swallow) {

        public static final String HP_MAX = "HP_MAX";
        public static final String MODIFIER = "MODIFIER";
        public static final String TURN_DAMAGE = "TURN_DAMAGE";
        public static final String IMMUNITY = "IMMUNITY";
        public static final String SWALLOW = "SWALLOW";
        public static final String COUNTED = "COUNTED";
        public static final String ABILITY = "ABILITY";
        public static final String POSSESSION = "POSSESSION";
        public static final String RISING = "RISING";

        /** A rider with no gullet (D203). */
        public MonsterRider(String kind, String action, UUID anchor, String anchorName, String rest,
                            TurnDamage turnDamage) {
            this(kind, action, anchor, anchorName, rest, turnDamage, null);
        }

        /** The same rider with its gullet changed (D204). */
        public MonsterRider withSwallow(Gullet changed) {
            return new MonsterRider(kind, action, anchor, anchorName, rest, turnDamage, changed);
        }
    }

    /**
     * A swallowing monster's gullet (D204), as a purple worm's (MM p.255): the creatures in it are the effect's affected
     * creatures. Damage they deal it is counted through each turn, and at the turn's end enough of it makes it save or
     * regurgitate them all, prone.
     *
     * @param condition          "swallowed" or "engulfed", the condition the creatures inside have
     * @param regurgitateDamage  the damage on a single turn that calls for the save; null for none
     * @param regurgitateDc      the Constitution save's DC; null when that much damage regurgitates them without one
     * @param escapeFeet         the movement a creature inside spends to leave the corpse, exiting prone, once the
     *                           swallower dies; null when none is given, as for an engulfing cube
     * @param takenThisTurn      the damage creatures inside have dealt it this turn
     */
    public record Gullet(@Schema(requiredMode = REQUIRED) String condition,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer regurgitateDamage,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer regurgitateDc,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Integer escapeFeet,
                         @Schema(requiredMode = REQUIRED) int takenThisTurn) {

        /** The same gullet having taken this much this turn. */
        public Gullet withTaken(int taken) {
            return new Gullet(condition, regurgitateDamage, regurgitateDc, escapeFeet, taken);
        }
    }

    /**
     * Damage a monster's hit deals at the start of a turn (D203), as a chain devil's 2d6 piercing while its chain
     * restrains (MM p.72).
     *
     * @param dice   the dice, such as "2d6"
     * @param type   the damage type; null for hit points lost, as a stirge's blood drain (MM p.284), which no defence
     *               changes
     * @param turnOf TARGET for the start of each of the creature's turns, USER for each of the monster's
     */
    public record TurnDamage(@Schema(requiredMode = REQUIRED) String dice,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String type,
                             @Schema(requiredMode = REQUIRED) String turnOf) {
    }

    /** The same effect holding what a monster's hit left (D203). */
    public SpellEffectEntry withMonsterRider(MonsterRider held) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                held);
    }

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

    /**
     * What a protective effect does back to a creature that hits in melee (D185): damage to one that hits its caster, as
     * Fire Shield's, or a save one of the named types makes when it hits a creature the effect covers, a failure giving
     * the conditions until the spell ends, as Holy Aura's blinding of a fiend or undead (PHB p.250, D195).
     *
     * @param dice          the damage dice, or null for none
     * @param type          the damage type
     * @param ability       the attacker's save, or null for none
     * @param dc            its DC, the caster's spell save DC when cast
     * @param conditions    what a failed save gives
     * @param attackerTypes the creature types that make the save; empty for any
     * @param whileTempHp   true when it strikes back only while its caster has temporary hit points, and ends once they
     *                      are gone, as Armor of Agathys' cold (PHB p.215, D199); null otherwise, and left out of the JSON
     */
    public record Retaliation(String dice, String type,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String ability,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Integer dc,
                              @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> conditions,
                              @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> attackerTypes,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Boolean whileTempHp) {

        public Retaliation {
            conditions = conditions != null ? List.copyOf(conditions) : List.of();
            attackerTypes = attackerTypes != null ? List.copyOf(attackerTypes) : List.of();
        }

        /** A retaliation that lasts as long as its effect (D185, D195). */
        public Retaliation(String dice, String type, String ability, Integer dc, List<String> conditions,
                           List<String> attackerTypes) {
            this(dice, type, ability, dc, conditions, attackerTypes, null);
        }

        /** Whether it lasts only while its caster has temporary hit points (D199). */
        public boolean needsTempHp() {
            return Boolean.TRUE.equals(whileTempHp);
        }

        /** Damage back, as Fire Shield's (D185). */
        public Retaliation(String dice, String type) {
            this(dice, type, null, null, List.of(), List.of());
        }

        /** Whether it covers the creatures the effect affects rather than its caster alone (D195). */
        public boolean coversAffected() {
            return ability != null;
        }
    }

    /** Exhaustion levels the effect gave a creature, which go when it ends (D185). */
    public record ExhaustionGiven(UUID creature, int levels) {
    }

    /**
     * What a cast leaves for its effect to roll later without its caster (D199).
     *
     * @param saveDc the caster's spell save DC when cast, as the DC of Wrathful Smite's Wisdom save on its hit (PHB
     *               p.289) or of Searing Smite's Constitution save at the start of the burning creature's turns (PHB
     *               p.274)
     * @param picked the creatures the caster picked at the cast for what follows the hit, there being no map, as those
     *               Hail of Thorns' thorns also fall on (PHB p.249); empty, and left out of the JSON, for none
     */
    public record Rider(@Schema(requiredMode = REQUIRED) int saveDc,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<UUID> picked) {

        public Rider {
            picked = picked != null ? List.copyOf(picked) : List.of();
        }
    }

    /** The same effect with what its rider needs later (D199). */
    public SpellEffectEntry withRider(Rider kept) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, kept,
                monsterRider);
    }

    public SpellEffectEntry {
        affected = affected != null ? List.copyOf(affected) : List.of();
        modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
        delayed = delayed != null ? List.copyOf(delayed) : List.of();
        exhaustion = exhaustion != null ? List.copyOf(exhaustion) : List.of();
        controlled = controlled != null ? List.copyOf(controlled) : List.of();
        rising = rising != null ? List.copyOf(rising) : List.of();
    }

    /**
     * The damage a Spell Effect deals as it ends (D189), as Delayed Blast Fireball's (PHB p.230).
     *
     * @param dice       the damage so far, such as "13d6", growing at the end of each of its caster's turns
     * @param type       the damage type
     * @param ability    the save's ability
     * @param dc         its DC, the caster's spell save DC when cast
     * @param halfOnSave whether a success halves it
     * @param grows      the dice it gains at the end of each of its caster's turns, or null
     */
    public record Detonation(@Schema(requiredMode = REQUIRED) String dice, String type,
                             @Schema(requiredMode = REQUIRED) String ability, @Schema(requiredMode = REQUIRED) int dc,
                             @Schema(requiredMode = REQUIRED) boolean halfOnSave, String grows) {
    }

    /** The same effect with this detonation (D189). */
    public SpellEffectEntry withDetonation(Detonation changed) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, countedSave, controlled, changed, rising, rider,
                monsterRider);
    }

    /** The same effect with these creatures to rise (D189). */
    public SpellEffectEntry withRising(List<UUID> creatures) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, creatures, rider,
                monsterRider);
    }

    /**
     * A tally of saves each creature makes at the end of each of its turns against the spell, as Contagion's (PHB
     * p.227, D187).
     *
     * @param ability        the save's ability
     * @param dc             its DC
     * @param successes      how many successes end the spell on the creature
     * @param failures       how many failures make its lasting effect take hold
     * @param onFailures     what the log reminds the DM of then, or null
     * @param endsConditions the spell's conditions that end on either outcome
     * @param tallies        each creature's count so far
     * @param failureCondition the condition the failures give for good, as Prismatic Spray's indigo ray's petrified
     *                       (PHB p.267, D189); null for none
     */
    public record CountedSave(String ability, int dc, int successes, int failures, String onFailures,
                              List<String> endsConditions, List<Tally> tallies,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String failureCondition) {

        public CountedSave {
            endsConditions = endsConditions != null ? List.copyOf(endsConditions) : List.of();
            tallies = tallies != null ? List.copyOf(tallies) : List.of();
        }

        /** Counted saves whose failures give no condition (D187). */
        public CountedSave(String ability, int dc, int successes, int failures, String onFailures,
                           List<String> endsConditions, List<Tally> tallies) {
            this(ability, dc, successes, failures, onFailures, endsConditions, tallies, null);
        }

        /** The same saves with these tallies. */
        public CountedSave withTallies(List<Tally> changed) {
            return new CountedSave(ability, dc, successes, failures, onFailures, endsConditions, changed,
                    failureCondition);
        }
    }

    /**
     * One creature's count of saves (D187).
     *
     * @param done true once either count is reached, so it rolls no more
     */
    public record Tally(@Schema(requiredMode = REQUIRED) UUID creature, @Schema(requiredMode = REQUIRED) int successes,
                        @Schema(requiredMode = REQUIRED) int failures, @Schema(requiredMode = REQUIRED) boolean done) {
    }

    /**
     * A creature the spell put under its caster's control, as Dominate Person's (PHB p.235, D187), with who controlled
     * it before, who gets it back when the spell ends or the condition goes.
     *
     * @param creature           the creature
     * @param previousController the user who controlled it before; null for the DM
     * @param condition          the condition that holds it, such as charmed
     */
    public record Controlled(@Schema(requiredMode = REQUIRED) UUID creature, UUID previousController,
                             String condition) {
    }

    /** The same effect with these counted saves (D187). */
    public SpellEffectEntry withCountedSave(CountedSave saves) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, saves, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect controlling these creatures (D187). */
    public SpellEffectEntry withControlled(List<Controlled> creatures) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, turnReminder, countedSave, creatures, detonation, rising, rider,
                monsterRider);
    }

    /** An effect without the 4th-level spells' additions (D185). */
    public SpellEffectEntry(UUID id, String spell, Integer slotLevel, UUID concentrationInstanceId,
                            int startedRound, Integer durationRounds, Integer usesLeft, SummonSpec summon,
                            List<UUID> affected, List<OngoingModifier> modifiers, String option, EffectSave repeatSave,
                            String endReminder, Boolean modifiersOnly) {
        this(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds, usesLeft, summon, affected,
                modifiers, option, repeatSave, endReminder, modifiersOnly, null, null, null, null, null, null, null, null,
                null, null);
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
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect with this summoning. */
    public SpellEffectEntry withSummon(SummonSpec spec) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, spec, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
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
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect on these creatures. */
    public SpellEffectEntry withAffected(List<UUID> creatures) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, creatures, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect with these modifiers left. */
    public SpellEffectEntry withModifiersLeft(List<OngoingModifier> left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, left, option, repeatSave, endReminder, modifiersOnly, delayed, damageLeft,
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect with this damage still to come (D185). */
    public SpellEffectEntry withDelayed(List<DelayedDamage> left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, left, damageLeft,
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect with this much damage left to deal (D185). */
    public SpellEffectEntry withDamageLeft(Integer left) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed, left,
                retaliation, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect dealing this back to a creature that hits its caster in melee (D185). */
    public SpellEffectEntry withRetaliation(Retaliation dealt) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, dealt, exhaustion, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect having given these exhaustion levels (D185). */
    public SpellEffectEntry withExhaustion(List<ExhaustionGiven> given) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, given, turnReminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /** The same effect with this reminder at the start of its caster's turns (D185). */
    public SpellEffectEntry withTurnReminder(String reminder) {
        return new SpellEffectEntry(id, spell, slotLevel, concentrationInstanceId, startedRound, durationRounds,
                usesLeft, summon, affected, modifiers, option, repeatSave, endReminder, modifiersOnly, delayed,
                damageLeft, retaliation, exhaustion, reminder, countedSave, controlled, detonation, rising, rider,
                monsterRider);
    }

    /**
     * Whether it exists only for damage still to come, as an instantaneous spell's delayed damage does (D185), so it ends
     * once that is dealt.
     */
    public boolean onlyForDelayed() {
        return concentrationInstanceId == null && summon == null && modifiers.isEmpty() && usesLeft == null
                && damageLeft == null && retaliation == null && turnReminder == null && exhaustion.isEmpty()
                && countedSave == null && controlled.isEmpty() && detonation == null && rising.isEmpty()
                && monsterRider == null;
    }

    /** Whether it exists only for its Ongoing Modifiers, so it ends with the last of them. */
    public boolean onlyForModifiers() {
        return Boolean.TRUE.equals(modifiersOnly);
    }
}
