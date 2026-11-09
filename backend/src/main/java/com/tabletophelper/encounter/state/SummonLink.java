package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.SummonRules;

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
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer commandedRound) {

    /** The participant's link, when it was summoned. */
    public static Optional<SummonLink> of(EncounterParticipant participant) {
        return Optional.ofNullable(participant.getSummon());
    }

    /** The same link, commanded in this round, or with no command for null. */
    public SummonLink withCommandedRound(Integer round) {
        return new SummonLink(effectId, spell, slotLevel, kind, initiativeMode, groupId, variant, creatureType,
                vanishesAtZero, cannotAttack, needsCommand, round);
    }

    /** Whether it follows its caster in the turn order. */
    public boolean followsCaster() {
        return initiativeMode == SummonRules.InitiativeMode.FOLLOWS_CASTER;
    }
}
