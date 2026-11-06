package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.ActionEconomy.ClassLevel;
import com.tabletophelper.rules.combat.Charge.SpellKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The action economy under the 2014 rules (D174, D172), typed out from the PHB: one action and the Attack action
 * (p.189–192), Extra Attack (p.72, and the classes and subclasses that have it), Action Surge (p.72), two-weapon
 * fighting (p.195), the reaction (p.190), the bonus-action-spell rule (p.202) and the incapacitating conditions
 * (appendix A). Nothing here is worked out from production tables.
 */
class ActionEconomyGoldenTest {

    private static final List<String> NO_CONDITIONS = List.of();

    // Cast on the caster's own turn
    private static final Charge HEALING_WORD = Charge.spell(ActionCost.BONUS_ACTION, SpellKind.LEVELLED, true);
    private static final Charge FIREBALL = Charge.spell(ActionCost.ACTION, SpellKind.LEVELLED, true);
    private static final Charge FIRE_BOLT = Charge.spell(ActionCost.ACTION, SpellKind.CANTRIP, true);
    private static final Charge SHILLELAGH = Charge.spell(ActionCost.BONUS_ACTION, SpellKind.CANTRIP, true);
    private static final Charge SHIELD_ON_OWN_TURN = Charge.spell(ActionCost.REACTION, SpellKind.LEVELLED, true);
    private static final Charge SHIELD_OFF_TURN = Charge.spell(ActionCost.REACTION, SpellKind.LEVELLED, false);

    private static Optional<String> check(TurnBudget budget, Charge charge) {
        return ActionEconomy.check(budget, charge, NO_CONDITIONS);
    }

    // Checks the charge is allowed, then pays it
    private static TurnBudget pay(TurnBudget budget, Charge charge) {
        assertEquals(Optional.empty(), check(budget, charge), "allowed: " + charge);
        return ActionEconomy.spend(budget, charge);
    }

    private static int attacks(String className, String subclassName, int level, String... features) {
        return ActionEconomy.attacksPerAction(List.of(features), List.of(new ClassLevel(className, subclassName, level)));
    }

    // ── Extra Attack (PHB p.72) ──────────────────────────────────

    @Nested
    @DisplayName("Attacks per Attack action")
    class AttacksPerAction {

        @Test
        @DisplayName("A Fighter attacks once to 4th level, twice from 5th, three times from 11th and four times at 20th")
        void fighter() {
            assertEquals(1, attacks("Fighter", null, 1));
            assertEquals(1, attacks("Fighter", "Champion", 4));
            assertEquals(2, attacks("Fighter", "Champion", 5, "Extra Attack"));
            assertEquals(2, attacks("Fighter", "Battle Master", 10, "Extra Attack"));
            assertEquals(3, attacks("Fighter", "Battle Master", 11, "Extra Attack", "Extra Attack (2)"));
            assertEquals(3, attacks("Fighter", "Battle Master", 19, "Extra Attack", "Extra Attack (2)"));
            assertEquals(4, attacks("Fighter", "Battle Master", 20, "Extra Attack", "Extra Attack (2)",
                    "Extra Attack (3)"));
        }

        @Test
        @DisplayName("Barbarians, Monks, Paladins and Rangers attack twice from 5th level, never more")
        void otherMartialClasses() {
            for (String className : List.of("Barbarian", "Monk", "Paladin", "Ranger")) {
                assertEquals(1, attacks(className, null, 4), className + " 4");
                assertEquals(2, attacks(className, null, 5, "Extra Attack"), className + " 5");
                assertEquals(2, attacks(className, null, 20, "Extra Attack"), className + " 20");
            }
        }

        @Test
        @DisplayName("The Valor and Swords Bard and the Bladesinger attack twice from 6th level, the Armorer and Battle Smith from 5th")
        void subclasses() {
            assertEquals(1, attacks("Bard", "College of Valor", 5));
            assertEquals(2, attacks("Bard", "College of Valor", 6, "Extra Attack"));
            assertEquals(2, attacks("Bard", "College of Swords", 6));
            assertEquals(1, attacks("Bard", "College of Lore", 20));
            assertEquals(1, attacks("Wizard", "Bladesinging", 5));
            assertEquals(2, attacks("Wizard", "Bladesinging", 6));
            assertEquals(1, attacks("Wizard", "School of Evocation", 20));
            assertEquals(2, attacks("Artificer", "Armorer", 5));
            assertEquals(2, attacks("Artificer", "Battle Smith", 5));
            assertEquals(1, attacks("Artificer", "Alchemist", 20));
        }

        @Test
        @DisplayName("Classes without Extra Attack attack once at any level, whatever features share the word")
        void noExtraAttack() {
            assertEquals(1, attacks("Rogue", "Thief", 20, "Sneak Attack"));
            assertEquals(1, attacks("Cleric", "Peace Domain", 20));
            assertEquals(1, attacks("Barbarian", null, 2, "Reckless Attack"));
            assertEquals(1, attacks("Monk", null, 3, "Ki-Fueled Attack"));
            // Hunter's Multiattack (Volley and Whirlwind Attack) is not Extra Attack; the Ranger's own is
            assertEquals(2, attacks("Ranger", "Hunter", 11, "Extra Attack", "Multiattack"));
        }

        @Test
        @DisplayName("The sheet's features count without class levels, ignoring case and spaces")
        void featuresAlone() {
            assertEquals(2, ActionEconomy.attacksPerAction(List.of(" extra attack "), List.of()));
            assertEquals(3, ActionEconomy.attacksPerAction(List.of("Extra Attack (2)"), List.of()));
            assertEquals(4, ActionEconomy.attacksPerAction(List.of("EXTRA ATTACK (3)"), List.of()));
            assertEquals(1, ActionEconomy.attacksPerAction(List.of(), List.of()));
        }

        @Test
        @DisplayName("Extra Attack from two classes doesn't add up (PHB p.164)")
        void multiclassDoesNotStack() {
            assertEquals(2, ActionEconomy.attacksPerAction(List.of("Extra Attack", "Extra Attack"),
                    List.of(new ClassLevel("Fighter", null, 5), new ClassLevel("Ranger", null, 5))));
            assertEquals(3, ActionEconomy.attacksPerAction(List.of("Extra Attack", "Extra Attack (2)"),
                    List.of(new ClassLevel("Fighter", null, 11), new ClassLevel("Paladin", null, 5))));
        }
    }

    // ── The Attack action and Action Surge ──────────────────────

    @Nested
    @DisplayName("The Attack action")
    class AttackAction {

        @Test
        @DisplayName("Without Extra Attack, one attack takes the action and a second is refused")
        void oneAttack() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.attack(1));

            assertEquals(1, budget.actionsUsed());
            assertTrue(budget.attackActionTaken());
            assertEquals(0, budget.attacksLeft());
            assertEquals(Optional.of("It has no attacks left in its Attack action and no action left this turn"),
                    check(budget, Charge.attack(1)));
        }

        @Test
        @DisplayName("A Fighter 5 attacks twice in one action, and a third attack is refused")
        void fighterFive() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.attack(2));
            assertEquals(1, budget.attacksLeft());
            budget = pay(budget, Charge.attack(2));

            assertEquals(1, budget.actionsUsed());
            assertEquals(0, budget.attacksLeft());
            assertTrue(check(budget, Charge.attack(2)).isPresent());
        }

        @Test
        @DisplayName("A Fighter 11 attacks three times and a Fighter 20 four, all in one action")
        void fighterElevenAndTwenty() {
            for (int perAction : List.of(3, 4)) {
                TurnBudget budget = TurnBudget.fresh();
                for (int i = 0; i < perAction; i++) {
                    budget = pay(budget, Charge.attack(perAction));
                }
                assertEquals(1, budget.actionsUsed());
                assertTrue(check(budget, Charge.attack(perAction)).isPresent(), perAction + " attacks");
            }
        }

        @Test
        @DisplayName("Action Surge adds an action: a Fighter 5 who has attacked twice takes a second Attack action")
        void actionSurge() {
            TurnBudget budget = pay(pay(TurnBudget.fresh(), Charge.attack(2)), Charge.attack(2));
            assertTrue(check(budget, Charge.attack(2)).isPresent());

            budget = ActionEconomy.grantAction(budget);
            assertEquals(2, budget.actions());
            budget = pay(budget, Charge.attack(2));
            assertEquals(2, budget.actionsUsed());
            assertEquals(1, budget.attacksLeft());
            budget = pay(budget, Charge.attack(2));

            assertEquals(Optional.of("It has no attacks left in its Attack action and no action left this turn"),
                    check(budget, Charge.attack(2)));
        }

        @Test
        @DisplayName("Action Surge lets a creature that has taken its action take another, such as Dash")
        void actionSurgeForAnotherAction() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.of(ActionCost.ACTION, true));
            assertEquals(Optional.of("It has no action left this turn"), check(budget, Charge.of(ActionCost.ACTION, true)));

            budget = pay(ActionEconomy.grantAction(budget), Charge.of(ActionCost.ACTION, true));

            assertEquals(2, budget.actionsUsed());
            assertFalse(budget.attackActionTaken());
        }

        @Test
        @DisplayName("After the action is taken for something else, an attack is refused")
        void actionTakenOtherwise() {
            TurnBudget budget = pay(TurnBudget.fresh(), FIREBALL);

            assertEquals(Optional.of("It has no action left this turn"), check(budget, Charge.attack(2)));
        }

        @Test
        @DisplayName("Something used in place of an attack opens the Attack action or uses one of its attacks")
        void replaceAttack() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.replaceAttack(2));
            assertEquals(1, budget.actionsUsed());
            assertEquals(1, budget.attacksLeft());

            budget = pay(budget, Charge.attack(2));

            assertEquals(0, budget.attacksLeft());
            assertTrue(check(budget, Charge.replaceAttack(2)).isPresent());
        }
    }

    // ── Two-weapon fighting (PHB p.195) ─────────────────────────

    @Nested
    @DisplayName("Off-hand attacks")
    class OffHand {

        @Test
        @DisplayName("An off-hand attack needs the Attack action this turn")
        void needsTheAttackAction() {
            assertEquals(Optional.of("An off-hand attack needs the Attack action first this turn"),
                    check(TurnBudget.fresh(), Charge.offHandAttack()));
            assertEquals(Optional.of("An off-hand attack needs the Attack action first this turn"),
                    check(pay(TurnBudget.fresh(), Charge.of(ActionCost.ACTION, true)), Charge.offHandAttack()));
        }

        @Test
        @DisplayName("After an attack it takes the bonus action, and a second is refused")
        void takesTheBonusAction() {
            TurnBudget budget = pay(pay(TurnBudget.fresh(), Charge.attack(1)), Charge.offHandAttack());

            assertTrue(budget.bonusActionUsed());
            assertEquals(Optional.of("It has already taken its bonus action this turn"),
                    check(budget, Charge.offHandAttack()));
        }

        @Test
        @DisplayName("It may come between a Fighter 5's two attacks")
        void betweenAttacks() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.attack(2));
            budget = pay(budget, Charge.offHandAttack());

            pay(budget, Charge.attack(2));
        }
    }

    // ── The reaction (PHB p.190) ─────────────────────────────────

    @Nested
    @DisplayName("Reactions")
    class Reactions {

        @Test
        @DisplayName("A reaction may be taken off-turn, once, until the creature's next turn gives it back")
        void offTurn() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.reactionAttack(false));

            assertTrue(budget.reactionUsed());
            assertEquals(Optional.of("It has already taken its reaction; it comes back at the start of its turn"),
                    check(budget, Charge.reactionAttack(false)));
            assertEquals(Optional.of("It has already taken its reaction; it comes back at the start of its turn"),
                    check(budget, SHIELD_OFF_TURN));
            assertEquals(Optional.empty(), check(TurnBudget.fresh(), Charge.reactionAttack(false)));
        }

        @Test
        @DisplayName("A reaction may be taken on its own turn too, and leaves the action alone")
        void onOwnTurn() {
            TurnBudget budget = pay(TurnBudget.fresh(), Charge.reactionAttack(true));

            pay(budget, Charge.attack(1));
        }

        @Test
        @DisplayName("Off its turn a creature takes no action, bonus action or attack; free costs and nothing are allowed")
        void offTurnOnlyReactions() {
            String reason = "Only a reaction can be taken outside its own turn";
            assertEquals(Optional.of(reason), check(TurnBudget.fresh(), Charge.of(ActionCost.ACTION, false)));
            assertEquals(Optional.of(reason), check(TurnBudget.fresh(), Charge.of(ActionCost.BONUS_ACTION, false)));
            assertEquals(Optional.of(reason), check(TurnBudget.fresh(), Charge.of(ActionCost.REPLACE_ATTACK, false)));
            assertEquals(Optional.of(reason),
                    check(TurnBudget.fresh(), Charge.spell(ActionCost.ACTION, SpellKind.CANTRIP, false)));
            assertEquals(Optional.empty(), check(TurnBudget.fresh(), Charge.of(ActionCost.FREE, false)));
            assertEquals(Optional.empty(), check(TurnBudget.fresh(), Charge.of(ActionCost.NONE, false)));
        }
    }

    // ── The bonus-action-spell rule (PHB p.202) ──────────────────

    @Nested
    @DisplayName("Spells cast as a bonus action")
    class BonusActionSpells {

        @Test
        @DisplayName("Healing Word, then Fireball is refused and Fire Bolt allowed")
        void bonusActionSpellFirst() {
            TurnBudget budget = pay(TurnBudget.fresh(), HEALING_WORD);
            assertTrue(budget.bonusActionSpellCast());

            assertEquals(Optional.of("It cast a spell as a bonus action this turn, so the only other spell it can "
                    + "cast is a cantrip with a casting time of 1 action"), check(budget, FIREBALL));
            pay(budget, FIRE_BOLT);
        }

        @Test
        @DisplayName("Fireball, then Healing Word is refused; Fire Bolt, then Healing Word is allowed")
        void otherSpellFirst() {
            TurnBudget afterFireball = pay(TurnBudget.fresh(), FIREBALL);
            assertTrue(afterFireball.otherSpellCast());
            assertEquals(Optional.of("It has already cast a spell other than a cantrip with a casting time of 1 "
                    + "action this turn, so it can't cast a spell as a bonus action"), check(afterFireball, HEALING_WORD));

            TurnBudget afterFireBolt = pay(TurnBudget.fresh(), FIRE_BOLT);
            assertFalse(afterFireBolt.otherSpellCast());
            pay(afterFireBolt, HEALING_WORD);
        }

        @Test
        @DisplayName("A cantrip cast as a bonus action counts as a bonus-action spell")
        void bonusActionCantrip() {
            TurnBudget budget = pay(TurnBudget.fresh(), SHILLELAGH);

            assertTrue(check(budget, FIREBALL).isPresent());
            pay(budget, FIRE_BOLT);
        }

        @Test
        @DisplayName("With Action Surge after Healing Word, a second 1-action cantrip is allowed and a levelled spell isn't")
        void withActionSurge() {
            TurnBudget budget = pay(pay(TurnBudget.fresh(), HEALING_WORD), FIRE_BOLT);
            budget = ActionEconomy.grantAction(budget);

            assertTrue(check(budget, FIREBALL).isPresent());
            pay(budget, FIRE_BOLT);
        }

        @Test
        @DisplayName("A reaction spell on its own turn counts; off its turn the rule doesn't apply")
        void reactionSpells() {
            TurnBudget afterHealingWord = pay(TurnBudget.fresh(), HEALING_WORD);
            assertTrue(check(afterHealingWord, SHIELD_ON_OWN_TURN).isPresent());
            TurnBudget offTurn = pay(afterHealingWord, SHIELD_OFF_TURN);
            assertFalse(offTurn.otherSpellCast());

            TurnBudget afterShield = pay(TurnBudget.fresh(), SHIELD_ON_OWN_TURN);
            assertTrue(afterShield.otherSpellCast());
            assertTrue(check(afterShield, HEALING_WORD).isPresent());
        }
    }

    // ── Incapacitated (PHB appendix A) ───────────────────────────

    @Nested
    @DisplayName("Incapacitated")
    class Incapacitated {

        @Test
        @DisplayName("Incapacitated, paralyzed, petrified, stunned or unconscious: no action, bonus action, attack or reaction")
        void noActionsOrReactions() {
            for (String condition : List.of("incapacitated", "paralyzed", "petrified", "stunned", "unconscious")) {
                List<String> conditions = List.of("prone", condition);
                for (Charge charge : List.of(Charge.attack(2), Charge.of(ActionCost.ACTION, true), HEALING_WORD,
                        Charge.reactionAttack(false), SHIELD_OFF_TURN, Charge.replaceAttack(1))) {
                    assertEquals(Optional.of("It is " + condition + ", so it can't take actions or reactions"),
                            ActionEconomy.check(TurnBudget.fresh(), charge, conditions), condition + ": " + charge);
                }
                assertTrue(ActionEconomy.incapacitated(conditions));
            }
        }

        @Test
        @DisplayName("Free costs and nothing are still allowed")
        void freeCostsAllowed() {
            List<String> stunned = List.of("Stunned");

            assertEquals(Optional.empty(), ActionEconomy.check(TurnBudget.fresh(), Charge.of(ActionCost.FREE, true), stunned));
            assertEquals(Optional.empty(), ActionEconomy.check(TurnBudget.fresh(), Charge.of(ActionCost.NONE, true), stunned));
        }

        @Test
        @DisplayName("Names match ignoring case and spaces; prone, poisoned, restrained, grappled and frightened don't incapacitate")
        void matching() {
            assertTrue(ActionEconomy.incapacitated(List.of(" STUNNED ")));
            assertFalse(ActionEconomy.incapacitated(List.of("prone", "poisoned", "restrained", "grappled", "frightened")));
            assertFalse(ActionEconomy.incapacitated(List.of()));
            assertFalse(ActionEconomy.incapacitated(null));
        }
    }

    // ── Action Surge ─────────────────────────────────────────────

    @Nested
    @DisplayName("Action Surge (PHB p.72)")
    class ActionSurge {

        @Test
        @DisplayName("It adds an action and marks Action Surge used, so a second Attack action is allowed")
        void addsAnAction() {
            TurnBudget attacked = ActionEconomy.spend(TurnBudget.fresh(), Charge.attack(1));
            assertTrue(ActionEconomy.check(attacked, Charge.attack(1), List.of()).isPresent());

            TurnBudget surged = ActionEconomy.actionSurge(attacked);

            assertEquals(2, surged.actions());
            assertTrue(surged.actionSurgeUsed());
            assertEquals(Optional.empty(), ActionEconomy.check(surged, Charge.attack(1), List.of()));
        }

        @Test
        @DisplayName("Only once on the same turn, and only on its own turn")
        void oncePerTurn() {
            assertEquals(Optional.empty(), ActionEconomy.actionSurgeCheck(TurnBudget.fresh(), true));
            assertEquals(Optional.of("It has already used Action Surge this turn; it can only once on the same turn"),
                    ActionEconomy.actionSurgeCheck(ActionEconomy.actionSurge(TurnBudget.fresh()), true));
            assertEquals(Optional.of("Action Surge is used on its own turn"),
                    ActionEconomy.actionSurgeCheck(TurnBudget.fresh(), false));
        }

        @Test
        @DisplayName("Spending keeps Action Surge used")
        void spendingKeepsIt() {
            TurnBudget surged = ActionEconomy.actionSurge(TurnBudget.fresh());

            assertTrue(ActionEconomy.spend(surged, Charge.of(ActionCost.ACTION, true)).actionSurgeUsed());
            assertTrue(ActionEconomy.grantAction(surged).actionSurgeUsed());
        }
    }

    // ── Spending ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Spending")
    class Spending {

        @Test
        @DisplayName("A fresh turn has one action and nothing spent")
        void fresh() {
            assertEquals(new TurnBudget(1, 0, false, 0, false, false, false, false, false, false, false, false),
                    TurnBudget.fresh());
        }

        @Test
        @DisplayName("Spending never refuses: over budget, the actions taken count past those it has")
        void overBudget() {
            TurnBudget budget = TurnBudget.fresh();
            for (int i = 0; i < 3; i++) {
                budget = ActionEconomy.spend(budget, Charge.of(ActionCost.ACTION, true));
            }
            budget = ActionEconomy.spend(budget, Charge.offHandAttack());
            budget = ActionEconomy.spend(budget, Charge.offHandAttack());

            assertEquals(1, budget.actions());
            assertEquals(3, budget.actionsUsed());
            assertTrue(budget.bonusActionUsed());
        }

        @Test
        @DisplayName("Free costs and nothing spend nothing")
        void freeCosts() {
            assertEquals(TurnBudget.fresh(), ActionEconomy.spend(TurnBudget.fresh(), Charge.of(ActionCost.FREE, true)));
            assertEquals(TurnBudget.fresh(), ActionEconomy.spend(TurnBudget.fresh(), Charge.of(ActionCost.NONE, false)));
        }

        @Test
        @DisplayName("A charge needs a cost, a spell kind and at least one attack per action")
        void invalidCharges() {
            assertThrows(NullPointerException.class, () -> new Charge(null, false, SpellKind.NONE, true, 1));
            assertThrows(NullPointerException.class, () -> new Charge(ActionCost.ACTION, false, null, true, 1));
            assertThrows(IllegalArgumentException.class, () -> Charge.attack(0));
            assertThrows(IllegalArgumentException.class,
                    () -> new TurnBudget(1, -1, false, 0, false, false, false, false, false, false, false, false));
        }
    }
}
