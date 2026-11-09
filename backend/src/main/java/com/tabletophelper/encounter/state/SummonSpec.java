package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The summoning a Spell Effect makes (D182), on {@link SpellEffectEntry#summon()}: what the casting may bring, and
 * whether its creatures are in the fight yet. The DM fills one that waits, as for Summon Lesser Demons, whose demons
 * the DM chooses (XGE p.167), and may replace the creatures of one placed.
 *
 * @param optionId   the option chosen, such as "8xCR1/4"; null until someone chooses it
 * @param multiplier how many times over the creatures come at the slot level (PHB p.225, XGE p.167)
 * @param roll       the d6 rolled on Summon Lesser Demons' table; null for other spells
 * @param status     whether the creatures wait for the DM or are placed
 */
public record SummonSpec(
        @JsonInclude(JsonInclude.Include.NON_NULL) String optionId,
        int multiplier,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer roll,
        Status status) {

    /** Whether the summoning's creatures are in the fight. */
    public enum Status { AWAITING_DM, PLACED }

    /** The same summoning, its creatures placed with the option. */
    public SummonSpec placed(String option) {
        return new SummonSpec(option != null ? option : optionId, multiplier, roll, Status.PLACED);
    }

    public boolean awaitingDm() {
        return status == Status.AWAITING_DM;
    }
}
