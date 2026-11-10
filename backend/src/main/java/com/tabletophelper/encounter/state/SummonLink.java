package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.SummonRules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What ties a summoned creature to the spell that brought it (D182), stored in {@code encounter_participants.summon}
 * (V34) on the creature's row; its caster is {@code summoned_by_participant_id}. The spell is a Spell Effect on the
 * caster ({@link SpellEffectEntry#summon()}) that owns its creatures, so they leave when it ends.
 *
 * @param effectId       the caster's Spell Effect the creature belongs to
 * @param spell          the spell's name
 * @param slotLevel      the slot level it was cast with; null when none was spent, as for one placed by the DM
 * @param kind           how the spell's creatures are made and governed
 * @param initiativeMode where its turns come: after the caster's, with its group's roll, or by its own
 * @param groupId        the creatures that share one initiative roll; null when it rolled alone or follows
 * @param variant        a Tasha's spirit's environment, mood or kind, such as "Land"; null for others
 * @param creatureType   the type it counts as instead of its own, such as "fey" for Conjure Animals' beasts (PHB p.225)
 *                       or a familiar's celestial, fey or fiend (PHB p.240); null for its own
 * @param vanishesAtZero whether it disappears when it drops to 0 hit points
 * @param cannotAttack   whether it can't attack, as a familiar can't (PHB p.240)
 * @param needsCommand   whether it acts only on its caster's command, as Animate Dead's undead (PHB p.212)
 * @param commandedRound the round its caster last commanded it, until its next turn ends; null without a command
 * @param damageResistances the damage types its spirit's variant resists beside its stat block's, such as the Air
 *                          Elemental Spirit's lightning and thunder (TCE p.111); empty for none
 * @param damageImmunities  the damage types its spirit's variant is immune to beside its stat block's, such as the
 *                          Fire Elemental Spirit's fire; empty for none
 * @param controlSaveDc  the caster's spell save DC that Summon Greater Demon's demon saves against at the end of each of
 *                       its turns to break free (XGE p.166); null for any other creature
 * @param uncontrolled   true once such a demon has broken free, the caster's control over for the rest of the spell;
 *                       null otherwise
 */
public record SummonLink(
        UUID effectId,
        String spell,
        Integer slotLevel,
        SummonRules.Kind kind,
        SummonRules.InitiativeMode initiativeMode,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID groupId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String variant,
        @JsonInclude(JsonInclude.Include.NON_NULL) String creatureType,
        boolean vanishesAtZero,
        boolean cannotAttack,
        boolean needsCommand,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer commandedRound,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> damageResistances,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> damageImmunities,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer controlSaveDc,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean uncontrolled) {

    public SummonLink {
        damageResistances = damageResistances != null ? List.copyOf(damageResistances) : List.of();
        damageImmunities = damageImmunities != null ? List.copyOf(damageImmunities) : List.of();
    }

    /** The participant's link, when it was summoned. */
    public static Optional<SummonLink> of(EncounterParticipant participant) {
        return Optional.ofNullable(participant.getSummon());
    }

    /** The same link, commanded in this round, or with no command for null. */
    public SummonLink withCommandedRound(Integer round) {
        return new SummonLink(effectId, spell, slotLevel, kind, initiativeMode, groupId, variant, creatureType,
                vanishesAtZero, cannotAttack, needsCommand, round, damageResistances, damageImmunities,
                controlSaveDc, uncontrolled);
    }

    /** The same link once the creature has broken free of its caster's control (XGE p.166). */
    public SummonLink withControlBroken() {
        return new SummonLink(effectId, spell, slotLevel, kind, initiativeMode, groupId, variant, creatureType,
                vanishesAtZero, cannotAttack, needsCommand, commandedRound, damageResistances, damageImmunities,
                controlSaveDc, true);
    }

    /** Whether it still has to save at the end of its turns to stay under its caster's control (XGE p.166). */
    public boolean savesToStayControlled() {
        return controlSaveDc != null && !Boolean.TRUE.equals(uncontrolled);
    }

    /** Whether it follows its caster in the turn order. */
    public boolean followsCaster() {
        return initiativeMode == SummonRules.InitiativeMode.FOLLOWS_CASTER;
    }
}
