package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param heldDie                     the die the entry gives its creature to roll, as Bardic Inspiration's does
 *                                    (M17); null for any other entry
 * @param wildShape                   the beast a druid has become, on its {@code wild shape} entry (PHB p.66), or
 *                                    the form a spell gives, on its entry, as Polymorph's {@code polymorphed}
 *                                    (D192); null for any other entry
 * @param weapon                      the one weapon the entry's feature imbues, as Sacred Weapon's (PHB p.86), by the
 *                                    name the attack options give it, such as "Longsword"; null for any other entry
 * @param endsOnAttackOrCast          true when it also ends as soon as its creature attacks or casts a spell, once
 *                                    that attack or spell is resolved, as the Invisibility spell's invisibility
 *                                    (PHB p.254) and Cloak of Shadows' (PHB p.63) do; null or false otherwise
 * @param bonus                       the number the entry's feature adds: Agile Parry's to AC (XGE p.34), Sharpen the
 *                                    Blade's to attack and damage rolls with its weapon (XGE p.35); null for any other
 *                                    entry
 * @param endsOnDamage                true when it ends as soon as its creature takes damage, as Hypnotic Pattern's
 *                                    charm does (PHB p.252, D185), whatever else ends it; null otherwise
 * @param saveOnDamage                the save its creature makes each time it takes damage, a success ending the
 *                                    spell, as Dominate Beast's (PHB p.234, D185); null for none
 * @param sourceOutOfSight            on a frightened entry, true when the DM has marked the source of the fear out of
 *                                    the creature's line of sight, so the fear gives no disadvantage (PHB p.290, D201);
 *                                    null otherwise
 * @param linkedTo                    the creature's other entry this one lasts while, ending with it, as a restraint
 *                                    "until this grapple ends" (MM p.324) or a paralysis "until this poison ends" (MM
 *                                    p.37) (D203); null otherwise
 * @param sourceAction                the monster action that gave it, as a giant constrictor snake's Constrict, which
 *                                    counts the grapples that attack holds (D203); null otherwise
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
        RepeatSave repeatSave,
        HeldDie heldDie,
        WildShapeForm wildShape,
        String weapon,
        Boolean endsOnAttackOrCast,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer bonus,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean endsOnDamage,
        @JsonInclude(JsonInclude.Include.NON_NULL) DamageSave saveOnDamage,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean sourceOutOfSight,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID linkedTo,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceAction
) {

    /** An entry tied to no other and given by no monster's action (D203). */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape,
                          String weapon, Boolean endsOnAttackOrCast, Integer bonus, Boolean endsOnDamage,
                          DamageSave saveOnDamage, Boolean sourceOutOfSight) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon, endsOnAttackOrCast, bonus,
                endsOnDamage, saveOnDamage, sourceOutOfSight, null, null);
    }

    /**
     * This entry tied to another of its creature's entries, so it ends when that one does, as a giant constrictor
     * snake's restraint ends with its grapple (MM p.324, D203): it keeps no duration or repeat save of its own.
     */
    public ConditionEntry linkedTo(UUID anchor) {
        return new ConditionEntry(id, name, null, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, EndRule.MANUAL, null, heldDie, wildShape, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, anchor, sourceAction);
    }

    /** This entry naming the monster action that gave it, as a grapple's attack (D203). */
    public ConditionEntry fromAction(String action) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, action);
    }

    /** An entry whose source the DM hasn't marked out of sight (D201). */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape,
                          String weapon, Boolean endsOnAttackOrCast, Integer bonus, Boolean endsOnDamage,
                          DamageSave saveOnDamage) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon, endsOnAttackOrCast, bonus,
                endsOnDamage, saveOnDamage, null);
    }

    /** An entry that taking damage neither ends nor tests (D185). */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape,
                          String weapon, Boolean endsOnAttackOrCast, Integer bonus) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon, endsOnAttackOrCast, bonus,
                null, null);
    }

    /** An entry that adds no number. */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape,
                          String weapon, Boolean endsOnAttackOrCast) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon, endsOnAttackOrCast, null);
    }

    /** An entry that attacking or casting doesn't end. */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape,
                          String weapon) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon, null);
    }

    /** An entry imbuing no weapon. */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie, WildShapeForm wildShape) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, null);
    }

    /** An entry with no beast form. */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave, HeldDie heldDie) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, heldDie, null);
    }

    /** An entry with no held die and no beast form. */
    public ConditionEntry(UUID id, String name, Integer duration, int appliedRound, String sourceSpellName,
                          UUID sourceParticipantId, Boolean sourceRequiresConcentration, UUID concentrationInstanceId,
                          EndRule endRule, RepeatSave repeatSave) {
        this(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration,
                concentrationInstanceId, endRule, repeatSave, null);
    }

    /**
     * A condition with no source, added by hand: it ends when removed, or after its duration at the start of
     * its creature's turn.
     */
    public static ConditionEntry manual(String name, Integer duration, int appliedRound) {
        return new ConditionEntry(UUID.randomUUID(), name, duration, appliedRound, null, null, null, null,
                duration != null ? EndRule.afterRounds(null) : EndRule.MANUAL, null);
    }

    /**
     * As {@link #manual(String, Integer, int)}, with the creature that caused it, as the DM records the source of a
     * fear added by hand (PHB p.290, D201): it ends as a manual condition does, and only records the source.
     */
    public static ConditionEntry manual(String name, Integer duration, int appliedRound, UUID sourceParticipantId) {
        return new ConditionEntry(UUID.randomUUID(), name, duration, appliedRound, null, sourceParticipantId, null,
                null, duration != null ? EndRule.afterRounds(null) : EndRule.MANUAL, null);
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
                sourceRequiresConcentration, concentrationInstanceId, endRule, save, heldDie, wildShape, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, sourceAction);
    }

    /** This entry carrying a creature's form, as Polymorph's {@code polymorphed} entry does (D192); null for none. */
    public ConditionEntry withForm(WildShapeForm form) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, form, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, sourceAction);
    }

    /** This entry with a count of its own, as Mirror Image's duplicates (PHB p.260, D196). */
    public ConditionEntry withBonus(Integer count) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon,
                endsOnAttackOrCast, count, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, sourceAction);
    }

    public ConditionEntry withHeldDie(HeldDie die) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, die, wildShape, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, sourceAction);
    }

    /** This entry, ending too when its creature attacks or casts a spell. */
    public ConditionEntry endingOnAttackOrCast() {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon,
                true, bonus, endsOnDamage, saveOnDamage, sourceOutOfSight, linkedTo, sourceAction);
    }

    /**
     * This entry, ending too when its creature takes damage, or with a save its creature makes then (D185); null for
     * either leaves it as it is.
     */
    public ConditionEntry onDamage(boolean ends, DamageSave save) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon,
                endsOnAttackOrCast, bonus, ends ? Boolean.TRUE : endsOnDamage, save != null ? save : saveOnDamage,
                sourceOutOfSight, linkedTo, sourceAction);
    }

    /**
     * This entry with the DM's mark that its source is out of the creature's line of sight, or with the mark taken off
     * (PHB p.290, D201).
     */
    public ConditionEntry withSourceOutOfSight(boolean outOfSight) {
        return new ConditionEntry(id, name, duration, appliedRound, sourceSpellName, sourceParticipantId,
                sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave, heldDie, wildShape, weapon,
                endsOnAttackOrCast, bonus, endsOnDamage, saveOnDamage, outOfSight ? Boolean.TRUE : null, linkedTo,
                sourceAction);
    }

    /** Whether the DM has marked its source out of sight (D201). */
    public boolean sourceMarkedOutOfSight() {
        return Boolean.TRUE.equals(sourceOutOfSight);
    }

    /** Whether it ends when its creature attacks or casts a spell. */
    public boolean endsWhenAttackingOrCasting() {
        return Boolean.TRUE.equals(endsOnAttackOrCast);
    }
}
