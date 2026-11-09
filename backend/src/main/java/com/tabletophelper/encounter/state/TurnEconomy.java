package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.rules.combat.TurnBudget;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What a participant has spent of its turn (D174), stored in {@code encounter_participants.turn_economy} (V30).
 * The start of its own turn makes it {@link #fresh()}; rules.combat.ActionEconomy works on it as a
 * {@link TurnBudget}, which has the same components.
 *
 * @param actions               the actions it has this turn: one, plus one for each Action Surge
 * @param actionsUsed           how many of them it has taken
 * @param attackActionTaken     it has taken the Attack action this turn
 * @param attacksLeft           the attacks left in its current Attack action (Extra Attack)
 * @param bonusActionUsed       it has taken its bonus action
 * @param reactionUsed          it has taken its reaction, which comes back at the start of its own turn
 * @param objectInteractionUsed it has had its free object interaction
 * @param bonusActionSpellCast  it has cast a spell as a bonus action this turn (PHB p.202)
 * @param otherSpellCast        it has cast a spell this turn other than a cantrip with a casting time of 1 action
 *                              and other than one cast as a bonus action (PHB p.202)
 * @param dashed                it has taken the Dash action (advisory)
 * @param disengaged            it has taken the Disengage action (advisory)
 * @param actionSurgeUsed       it has used Action Surge this turn, which it may only once (PHB p.72). Written only
 *                              when true, so a record stored before it existed reads back unchanged, as false
 * @param attackedOrDamaged     it has attacked or taken damage since its own last turn ended, which keeps a Rage going
 *                              (PHB p.48, M17). The start of its turn keeps it and the end of that turn clears it.
 *                              Written only when true, as actionSurgeUsed is
 */
public record TurnEconomy(
        @Schema(requiredMode = REQUIRED) int actions,
        @Schema(requiredMode = REQUIRED) int actionsUsed,
        @Schema(requiredMode = REQUIRED) boolean attackActionTaken,
        @Schema(requiredMode = REQUIRED) int attacksLeft,
        @Schema(requiredMode = REQUIRED) boolean bonusActionUsed,
        @Schema(requiredMode = REQUIRED) boolean reactionUsed,
        @Schema(requiredMode = REQUIRED) boolean objectInteractionUsed,
        @Schema(requiredMode = REQUIRED) boolean bonusActionSpellCast,
        @Schema(requiredMode = REQUIRED) boolean otherSpellCast,
        @Schema(requiredMode = REQUIRED) boolean dashed,
        @Schema(requiredMode = REQUIRED) boolean disengaged,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean actionSurgeUsed,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean attackedOrDamaged
) {

    /** An economy that records no attack or damage since the creature's last turn. */
    public TurnEconomy(int actions, int actionsUsed, boolean attackActionTaken, int attacksLeft,
                       boolean bonusActionUsed, boolean reactionUsed, boolean objectInteractionUsed,
                       boolean bonusActionSpellCast, boolean otherSpellCast, boolean dashed, boolean disengaged,
                       boolean actionSurgeUsed) {
        this(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                false);
    }

    /** The economy at the start of a turn: one action and nothing spent. V30's column default is this record. */
    public static TurnEconomy fresh() {
        return of(TurnBudget.fresh());
    }

    /** The same economy as the rules module's budget. */
    public static TurnEconomy of(TurnBudget budget) {
        return new TurnEconomy(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(),
                budget.attacksLeft(), budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), budget.dashed(), budget.disengaged(),
                budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /** The same economy, recording whether the creature has attacked or taken damage since its last turn. */
    public TurnEconomy withAttackedOrDamaged(boolean value) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed, value);
    }

    /** This economy as the rules module's budget. */
    public TurnBudget budget() {
        return new TurnBudget(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged);
    }
}
