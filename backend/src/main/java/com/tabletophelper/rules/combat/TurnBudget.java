package com.tabletophelper.rules.combat;

/**
 * What a creature has spent of its turn (PHB p.189–193, D174): what {@link ActionEconomy} works on. The encounter
 * stores it as its own record, {@code encounter/state/TurnEconomy}, with the same components.
 *
 * @param actions              the actions it has this turn: one, plus one for each Action Surge
 * @param actionsUsed          how many of them it has taken
 * @param attackActionTaken    it has taken the Attack action this turn
 * @param attacksLeft          the attacks left in its current Attack action (Extra Attack, PHB p.72)
 * @param bonusActionUsed      it has taken its bonus action
 * @param reactionUsed         it has taken its reaction, which comes back at the start of its own turn (PHB p.190)
 * @param objectInteractionUsed it has had its free object interaction (PHB p.190)
 * @param bonusActionSpellCast it has cast a spell as a bonus action this turn (PHB p.202)
 * @param otherSpellCast       it has cast a spell this turn other than a cantrip with a casting time of 1 action
 *                             and other than one cast as a bonus action (PHB p.202)
 * @param dashed               it has taken the Dash action (advisory)
 * @param disengaged           it has taken the Disengage action (advisory)
 * @param actionSurgeUsed      it has used Action Surge this turn, which it may only once (PHB p.72)
 * @param attackedOrDamaged    it has attacked or taken damage since its own last turn ended, which keeps a Rage going
 *                             (PHB p.48). Unlike the rest it isn't spent or refreshed by the turn: the start of the
 *                             creature's turn keeps it, and the end of that turn clears it
 */
public record TurnBudget(int actions, int actionsUsed, boolean attackActionTaken, int attacksLeft,
                         boolean bonusActionUsed, boolean reactionUsed, boolean objectInteractionUsed,
                         boolean bonusActionSpellCast, boolean otherSpellCast, boolean dashed, boolean disengaged,
                         boolean actionSurgeUsed, boolean attackedOrDamaged) {

    public TurnBudget {
        if (actions < 0 || actionsUsed < 0 || attacksLeft < 0) {
            throw new IllegalArgumentException("Impossible budget: " + actionsUsed + " of " + actions + " actions, "
                    + attacksLeft + " attacks left");
        }
    }

    /** A budget that records no attack or damage since the creature's last turn. */
    public TurnBudget(int actions, int actionsUsed, boolean attackActionTaken, int attacksLeft,
                      boolean bonusActionUsed, boolean reactionUsed, boolean objectInteractionUsed,
                      boolean bonusActionSpellCast, boolean otherSpellCast, boolean dashed, boolean disengaged,
                      boolean actionSurgeUsed) {
        this(actions, actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed, reactionUsed,
                objectInteractionUsed, bonusActionSpellCast, otherSpellCast, dashed, disengaged, actionSurgeUsed,
                false);
    }

    /** The budget at the start of a creature's turn: one action and nothing spent. */
    public static TurnBudget fresh() {
        return new TurnBudget(1, 0, false, 0, false, false, false, false, false, false, false, false);
    }

    /** Whether it has an action it hasn't taken. */
    public boolean actionLeft() {
        return actionsUsed < actions;
    }
}
