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
 * @param sneakAttackTurn       the turn of the encounter on which it last dealt Sneak Attack damage, as EncounterTurns'
 *                              stamp names it: Sneak Attack is once per turn, anyone's turn (PHB p.96, M17), so an
 *                              opportunity attack on another creature's turn may have it again. Written only when set
 * @param martialArtsAttack     it has taken the Attack action this turn with an unarmed strike or a monk weapon, which
 *                              Martial Arts' bonus-action unarmed strike and Flurry of Blows follow (PHB p.78, M17).
 *                              Written only when true
 * @param flurryStrikesLeft     the unarmed strikes left from the Flurry of Blows it started this turn (PHB p.78, M17).
 *                              Written only when more than 0
 * @param lastMeleeHit          its latest melee weapon hit, which Divine Smite and Stunning Strike may follow on the
 *                              same turn (PHB p.85, p.79, M17), or a Kensei monk's latest hit with a kensei weapon,
 *                              ranged ones included, which Deft Strike may follow (XGE p.34); null after a miss or before
 *                              any. Written only when set
 * @param deftStrikeUsed        it has used Deft Strike this turn, which it may once on each of its turns (XGE p.34).
 *                              Written only when true
 * @param unerringAccuracyUsed  it has rerolled a missed attack with Unerring Accuracy this turn, which it may once on
 *                              each of its turns (XGE p.35). Written only when true
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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean attackedOrDamaged,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sneakAttackTurn,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean martialArtsAttack,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int flurryStrikesLeft,
        @JsonInclude(JsonInclude.Include.NON_NULL) WeaponHit lastMeleeHit,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean deftStrikeUsed,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean unerringAccuracyUsed
) {

    /** An economy without a Kensei monk's records of the turn (XGE p.34-35). */
    public TurnEconomy(int actions, int actionsUsed, boolean attackActionTaken, int attacksLeft,
                       boolean bonusActionUsed, boolean reactionUsed, boolean objectInteractionUsed,
                       boolean bonusActionSpellCast, boolean otherSpellCast, boolean dashed, boolean disengaged,
                       boolean actionSurgeUsed, boolean attackedOrDamaged, String sneakAttackTurn,
                       boolean martialArtsAttack, int flurryStrikesLeft, WeaponHit lastMeleeHit) {
        this(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, martialArtsAttack, flurryStrikesLeft, lastMeleeHit, false, false);
    }

    /** An economy with none of the class features' records of the turn (M17). */
    public TurnEconomy(int actions, int actionsUsed, boolean attackActionTaken, int attacksLeft,
                       boolean bonusActionUsed, boolean reactionUsed, boolean objectInteractionUsed,
                       boolean bonusActionSpellCast, boolean otherSpellCast, boolean dashed, boolean disengaged,
                       boolean actionSurgeUsed, boolean attackedOrDamaged) {
        this(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, null, false, 0, null);
    }

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

    /**
     * The same economy as the rules module's budget, with none of the class features' records of the turn; to keep
     * them, use {@link #withBudget}.
     */
    public static TurnEconomy of(TurnBudget budget) {
        return new TurnEconomy(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(),
                budget.attacksLeft(), budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), budget.dashed(), budget.disengaged(),
                budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /**
     * The budget's economy, keeping this one's records of the turn the budget doesn't have: Sneak Attack's turn, Martial
     * Arts, Flurry of Blows and the last melee hit (M17), and Deft Strike and Unerring Accuracy (XGE p.34-35).
     */
    public TurnEconomy withBudget(TurnBudget budget) {
        return new TurnEconomy(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(),
                budget.attacksLeft(), budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), budget.dashed(), budget.disengaged(),
                budget.actionSurgeUsed(), budget.attackedOrDamaged(), sneakAttackTurn, martialArtsAttack,
                flurryStrikesLeft, lastMeleeHit, deftStrikeUsed, unerringAccuracyUsed);
    }

    /** The same economy, recording whether the creature has attacked or taken damage since its last turn. */
    public TurnEconomy withAttackedOrDamaged(boolean value) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed, value,
                sneakAttackTurn, martialArtsAttack, flurryStrikesLeft, lastMeleeHit, deftStrikeUsed, unerringAccuracyUsed);
    }

    /** The same economy, recording that Sneak Attack was dealt on the turn the stamp names (PHB p.96). */
    public TurnEconomy withSneakAttackTurn(String turn) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, turn, martialArtsAttack, flurryStrikesLeft, lastMeleeHit, deftStrikeUsed,
                unerringAccuracyUsed);
    }

    /** The same economy, recording an Attack action with an unarmed strike or a monk weapon (PHB p.78). */
    public TurnEconomy withMartialArtsAttack(boolean value) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, value, flurryStrikesLeft, lastMeleeHit, deftStrikeUsed,
                unerringAccuracyUsed);
    }

    /** The same economy with this many Flurry of Blows strikes left (PHB p.78). */
    public TurnEconomy withFlurryStrikesLeft(int strikes) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, martialArtsAttack, strikes, lastMeleeHit, deftStrikeUsed,
                unerringAccuracyUsed);
    }

    /** The same economy with this latest melee weapon hit, or none after a miss. */
    public TurnEconomy withLastMeleeHit(WeaponHit hit) {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, martialArtsAttack, flurryStrikesLeft, hit, deftStrikeUsed,
                unerringAccuracyUsed);
    }

    /** The same economy, recording that Deft Strike was used this turn (XGE p.34). */
    public TurnEconomy withDeftStrikeUsed() {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, martialArtsAttack, flurryStrikesLeft, lastMeleeHit, true,
                unerringAccuracyUsed);
    }

    /** The same economy, recording that Unerring Accuracy rerolled a miss this turn (XGE p.35). */
    public TurnEconomy withUnerringAccuracyUsed() {
        return new TurnEconomy(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged, sneakAttackTurn, martialArtsAttack, flurryStrikesLeft, lastMeleeHit, deftStrikeUsed,
                true);
    }

    /** This economy as the rules module's budget. */
    public TurnBudget budget() {
        return new TurnBudget(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                attackedOrDamaged);
    }
}
