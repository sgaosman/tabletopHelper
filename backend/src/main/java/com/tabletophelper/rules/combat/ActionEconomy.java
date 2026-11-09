package com.tabletophelper.rules.combat;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The action economy under the 2014 rules (PHB p.189–195, p.202, appendix A; D174): what a creature may still do
 * this turn, and what doing it spends. It answers with a reason rather than refusing, so the encounter decides who
 * is held to the budget (players) and who only goes over it (the DM).
 * <ul>
 *   <li>A creature has one action on its turn, plus one for each Action Surge (p.72), and a bonus action and
 *       actions only on its own turn. Its reaction is one per round, back at the start of its own turn, and may be
 *       taken on any turn (p.190).</li>
 *   <li>The first attack of a turn takes the Attack action, which gives as many attacks as Extra Attack allows
 *       (p.72); later attacks use those up, and another Attack action needs another action.</li>
 *   <li>An off-hand attack is a bonus action that needs the Attack action first (p.195).</li>
 *   <li>After a spell cast as a bonus action, the only other spell that turn is a cantrip with a casting time of
 *       1 action, and in either order: a spell other than such a cantrip, cast first, rules out a bonus-action spell
 *       (p.202). Only spells cast on the caster's own turn count.</li>
 *   <li>An incapacitated creature (incapacitated, paralyzed, petrified, stunned or unconscious) takes no actions,
 *       bonus actions or reactions (p.290–292); what costs nothing from the budget is still allowed.</li>
 * </ul>
 */
public final class ActionEconomy {

    private ActionEconomy() {}

    /** A class a creature has levels in, with its subclass, for Extra Attack. */
    public record ClassLevel(String className, String subclassName, int level) {}

    /**
     * Why the creature can't pay the charge now, or empty when it can.
     *
     * @param conditions the names of the creature's conditions
     */
    public static Optional<String> check(TurnBudget budget, Charge charge, Collection<String> conditions) {
        ActionCost cost = charge.cost();
        if (cost == ActionCost.NONE) {
            return Optional.empty();
        }
        Optional<String> incapacitating = incapacitatingCondition(conditions);
        if (incapacitating.isPresent() && cost != ActionCost.FREE) {
            return Optional.of("It is " + incapacitating.get() + ", so it can't take actions or reactions");
        }
        if (!charge.ownTurn() && cost != ActionCost.REACTION && cost != ActionCost.FREE) {
            return Optional.of("Only a reaction can be taken outside its own turn");
        }
        Optional<String> budgetReason = switch (cost) {
            case ACTION, REPLACE_ATTACK -> charge.attack() || cost == ActionCost.REPLACE_ATTACK
                    ? attackReason(budget)
                    : budget.actionLeft() ? Optional.empty() : Optional.of("It has no action left this turn");
            case BONUS_ACTION -> budget.bonusActionUsed()
                    ? Optional.of("It has already taken its bonus action this turn")
                    : charge.attack() && !budget.attackActionTaken()
                    ? Optional.of("An off-hand attack needs the Attack action first this turn")
                    : Optional.empty();
            case REACTION -> budget.reactionUsed()
                    ? Optional.of("It has already taken its reaction; it comes back at the start of its turn")
                    : Optional.empty();
            case FREE, NONE -> Optional.empty();
        };
        if (budgetReason.isPresent()) {
            return budgetReason;
        }
        return spellReason(budget, charge);
    }

    private static Optional<String> attackReason(TurnBudget budget) {
        if (budget.attackActionTaken() && budget.attacksLeft() > 0) {
            return Optional.empty();
        }
        if (budget.actionLeft()) {
            return Optional.empty();
        }
        return Optional.of(budget.attackActionTaken()
                ? "It has no attacks left in its Attack action and no action left this turn"
                : "It has no action left this turn");
    }

    // The bonus-action-spell rule (PHB p.202), on the caster's own turn and in either order
    private static Optional<String> spellReason(TurnBudget budget, Charge charge) {
        if (charge.spell() == Charge.SpellKind.NONE || !charge.ownTurn()) {
            return Optional.empty();
        }
        if (charge.cost() == ActionCost.BONUS_ACTION && budget.otherSpellCast()) {
            return Optional.of("It has already cast a spell other than a cantrip with a casting time of 1 action "
                    + "this turn, so it can't cast a spell as a bonus action");
        }
        if (charge.cost() != ActionCost.BONUS_ACTION && budget.bonusActionSpellCast() && !actionCantrip(charge)) {
            return Optional.of("It cast a spell as a bonus action this turn, so the only other spell it can cast "
                    + "is a cantrip with a casting time of 1 action");
        }
        return Optional.empty();
    }

    private static boolean actionCantrip(Charge charge) {
        return charge.spell() == Charge.SpellKind.CANTRIP && charge.cost() == ActionCost.ACTION;
    }

    /**
     * The budget after paying the charge. It never refuses: a charge over budget (the DM's) is recorded as spent
     * all the same, the actions taken counting past those it has, and it gives nothing back: an attack with no
     * action left opens no further attacks.
     */
    public static TurnBudget spend(TurnBudget budget, Charge charge) {
        int actionsUsed = budget.actionsUsed();
        boolean attackActionTaken = budget.attackActionTaken();
        int attacksLeft = budget.attacksLeft();
        boolean bonusActionUsed = budget.bonusActionUsed();
        boolean reactionUsed = budget.reactionUsed();
        boolean bonusActionSpellCast = budget.bonusActionSpellCast();
        boolean otherSpellCast = budget.otherSpellCast();

        switch (charge.cost()) {
            case ACTION, REPLACE_ATTACK -> {
                if (charge.attack() || charge.cost() == ActionCost.REPLACE_ATTACK) {
                    if (attackActionTaken && attacksLeft > 0) {
                        attacksLeft--;
                    } else {
                        // An Attack action taken over budget (the DM's) uses no action the creature has, so it
                        // leaves no attacks behind for later
                        boolean actionLeft = budget.actionLeft();
                        actionsUsed++;
                        attackActionTaken = true;
                        attacksLeft = actionLeft ? charge.attacksPerAction() - 1 : 0;
                    }
                } else {
                    actionsUsed++;
                }
            }
            case BONUS_ACTION -> bonusActionUsed = true;
            case REACTION -> reactionUsed = true;
            case FREE, NONE -> {
                // Nothing from the budget
            }
        }
        if (charge.spell() != Charge.SpellKind.NONE && charge.ownTurn()) {
            if (charge.cost() == ActionCost.BONUS_ACTION) {
                bonusActionSpellCast = true;
            } else if (!actionCantrip(charge)) {
                otherSpellCast = true;
            }
        }
        return new TurnBudget(budget.actions(), actionsUsed, attackActionTaken, attacksLeft, bonusActionUsed,
                reactionUsed, budget.objectInteractionUsed(), bonusActionSpellCast, otherSpellCast, budget.dashed(),
                budget.disengaged(), budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /** The budget with one more action, as Action Surge gives (PHB p.72). */
    public static TurnBudget grantAction(TurnBudget budget) {
        return new TurnBudget(budget.actions() + 1, budget.actionsUsed(), budget.attackActionTaken(),
                budget.attacksLeft(), budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), budget.dashed(), budget.disengaged(),
                budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /**
     * Why the creature can't use Action Surge now, or empty when it can: only on its own turn, and only once on the
     * same turn, even with two uses (PHB p.72).
     */
    public static Optional<String> actionSurgeCheck(TurnBudget budget, boolean ownTurn) {
        if (!ownTurn) {
            return Optional.of("Action Surge is used on its own turn");
        }
        if (budget.actionSurgeUsed()) {
            return Optional.of("It has already used Action Surge this turn; it can only once on the same turn");
        }
        return Optional.empty();
    }

    /** The budget after Action Surge: one more action, and Action Surge used this turn (PHB p.72). */
    public static TurnBudget actionSurge(TurnBudget budget) {
        TurnBudget granted = grantAction(budget);
        return new TurnBudget(granted.actions(), granted.actionsUsed(), granted.attackActionTaken(),
                granted.attacksLeft(), granted.bonusActionUsed(), granted.reactionUsed(),
                granted.objectInteractionUsed(), granted.bonusActionSpellCast(), granted.otherSpellCast(),
                granted.dashed(), granted.disengaged(), true, granted.attackedOrDamaged());
    }

    /** The budget with the Dash action taken this turn, which the encounter shows (PHB p.192). */
    public static TurnBudget dash(TurnBudget budget) {
        return new TurnBudget(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(), budget.attacksLeft(),
                budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), true, budget.disengaged(),
                budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /** The budget with the Disengage action taken this turn, which the encounter shows (PHB p.192). */
    public static TurnBudget disengage(TurnBudget budget) {
        return new TurnBudget(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(), budget.attacksLeft(),
                budget.bonusActionUsed(), budget.reactionUsed(), budget.objectInteractionUsed(),
                budget.bonusActionSpellCast(), budget.otherSpellCast(), budget.dashed(), true,
                budget.actionSurgeUsed(), budget.attackedOrDamaged());
    }

    /**
     * Why the creature can't interact with an object for free now, or empty when it can: one interaction a turn
     * comes free with its move or action, and another needs the Use an Object action (PHB p.190); an incapacitated
     * creature has neither (p.290).
     *
     * @param conditions the names of the creature's conditions
     */
    public static Optional<String> objectInteractionCheck(TurnBudget budget, Collection<String> conditions) {
        // The free interaction comes with a move or an action, which an incapacitated creature can't take (p.290)
        Optional<String> incapacitating = incapacitatingCondition(conditions);
        if (incapacitating.isPresent()) {
            return Optional.of("It is " + incapacitating.get() + ", so it can't take actions or reactions");
        }
        return budget.objectInteractionUsed()
                ? Optional.of("It has already had its free object interaction this turn; another needs the Use an "
                + "Object action")
                : Optional.empty();
    }

    /** The budget with the free object interaction had this turn (PHB p.190). */
    public static TurnBudget objectInteraction(TurnBudget budget) {
        return new TurnBudget(budget.actions(), budget.actionsUsed(), budget.attackActionTaken(), budget.attacksLeft(),
                budget.bonusActionUsed(), budget.reactionUsed(), true, budget.bonusActionSpellCast(),
                budget.otherSpellCast(), budget.dashed(), budget.disengaged(), budget.actionSurgeUsed(),
                budget.attackedOrDamaged());
    }

    /**
     * The attacks one Attack action gives a character (PHB p.72): 2 with Extra Attack, 3 for a Fighter of 11th
     * level and 4 for one of 20th. The data names the features "Extra Attack" (Barbarian, Fighter, Monk, Paladin and
     * Ranger at 5th level, the Armorer and Battle Smith Artificers at 5th, the College of Valor and College of Swords
     * Bard and the Bladesinging Wizard at 6th), "Extra Attack (2)" (Fighter 11th) and "Extra Attack (3)" (Fighter
     * 20th). The class levels count as well as the sheet's features, since some subclasses' features aren't in the
     * data. Extra Attack from more than one class doesn't add up (PHB p.164).
     *
     * @param featureNames the names of the character's features
     * @param classes      its classes and their levels
     */
    public static int attacksPerAction(Collection<String> featureNames, Collection<ClassLevel> classes) {
        int attacks = 1;
        for (String name : featureNames) {
            String feature = normalise(name);
            if (feature.equals("extra attack (3)")) {
                attacks = Math.max(attacks, 4);
            } else if (feature.equals("extra attack (2)")) {
                attacks = Math.max(attacks, 3);
            } else if (feature.equals("extra attack")) {
                attacks = Math.max(attacks, 2);
            }
        }
        for (ClassLevel entry : classes) {
            attacks = Math.max(attacks, attacksFromClass(normalise(entry.className()), normalise(entry.subclassName()),
                    entry.level()));
        }
        return attacks;
    }

    private static int attacksFromClass(String className, String subclassName, int level) {
        return switch (className) {
            case "fighter" -> level >= 20 ? 4 : level >= 11 ? 3 : level >= 5 ? 2 : 1;
            case "barbarian", "monk", "paladin", "ranger" -> level >= 5 ? 2 : 1;
            case "artificer" -> (subclassName.equals("armorer") || subclassName.equals("battle smith")) && level >= 5
                    ? 2 : 1;
            case "bard" -> (subclassName.equals("college of valor") || subclassName.equals("college of swords"))
                    && level >= 6 ? 2 : 1;
            case "wizard" -> subclassName.equals("bladesinging") && level >= 6 ? 2 : 1;
            default -> 1;
        };
    }

    /** Whether the conditions leave a creature incapacitated, so it takes no actions or reactions. */
    public static boolean incapacitated(Collection<String> conditions) {
        return incapacitatingCondition(conditions).isPresent();
    }

    private static Optional<String> incapacitatingCondition(Collection<String> conditions) {
        if (conditions == null) {
            return Optional.empty();
        }
        return Conditions.INCAPACITATING.stream()
                .filter(name -> Conditions.has(conditions, name))
                .findFirst();
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
