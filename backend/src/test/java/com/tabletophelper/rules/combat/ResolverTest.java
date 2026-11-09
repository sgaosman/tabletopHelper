package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Resolver (D160): attacks, saves, automatic Effects and Effects on their user, resolved over scripted dice
 * into outcomes, with nothing changed. The Resolver cases of the old SpellResolverEngineTest are here: the
 * attack, save, automatic, self and multi-target nests; the template reading went to SpellEffectsTest and the
 * save modifiers to CreatureStatsTest.
 */
class ResolverTest {

    private static final UUID GOBLIN = UUID.randomUUID();
    private static final UUID ORC = UUID.randomUUID();

    private static Resolver.Target target(UUID id, int ac, int saveModifier, String... conditions) {
        return new Resolver.Target(id, ac, saveModifier, List.of(conditions));
    }

    private static Effect.DamagePart part(String dice, String type) {
        return new Effect.DamagePart(Dice.parse(dice), type);
    }

    private static Effect attack(int bonus, RollMode mode, boolean withinFiveFeet, boolean forced, Effect.DamagePart... damage) {
        return new Effect("Fire Bolt", new Effect.Attack(bonus, mode, withinFiveFeet, forced), List.of(damage), null,
                List.of(), false, null);
    }

    private static Effect save(Ability ability, int dc, boolean half, List<String> conditions, Effect.DamagePart... damage) {
        return new Effect("Fireball", new Effect.Save(ability, dc, half), List.of(damage), null, conditions, false, null);
    }

    // A +5 melee attack whose hit calls for the save against the conditions it guards
    private static Effect hitWithSave(Effect.HitSave hitSave, List<String> conditions, Effect.DamagePart... damage) {
        return new Effect("Bite", new Effect.Attack(5, RollMode.NORMAL, true, false, hitSave), List.of(damage), null,
                conditions, false, null);
    }

    private static int total(List<Resolver.Damage> damage) {
        return damage.stream().mapToInt(Resolver.Damage::amount).sum();
    }

    @Nested
    @DisplayName("Attack rolls")
    class Attacks {

        @Test
        @DisplayName("A roll plus the bonus that reaches the AC hits and rolls the damage (was SpellResolverEngineTest: roll + bonus >= AC)")
        void hit() {
            var dice = Rng.scripted(12, 7);
            var outcome = new Resolver(dice).resolve(attack(5, RollMode.NORMAL, false, false, part("1d10", "fire")),
                    List.of(target(GOBLIN, 15, 0))).getFirst();

            assertEquals(Outcome.HIT, outcome.outcome());
            assertEquals(12, outcome.d20());
            assertEquals(17, outcome.total());
            assertEquals(List.of(new Resolver.Damage("fire", 7)), outcome.damage());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("A total below the AC misses and rolls no damage (was: roll + bonus < AC)")
        void miss() {
            var dice = Rng.scripted(5);
            var outcome = new Resolver(dice).resolve(attack(5, RollMode.NORMAL, false, false, part("1d10", "fire")),
                    List.of(target(GOBLIN, 15, 0))).getFirst();

            assertEquals(Outcome.MISS, outcome.outcome());
            assertTrue(outcome.damage().isEmpty());
            assertTrue(outcome.conditions().isEmpty());
        }

        @Test
        @DisplayName("A natural 20 is a critical hit that doubles the dice but not the modifier (was: natural 20)")
        void naturalTwenty() {
            var outcome = new Resolver(Rng.scripted(20, 6, 5)).resolve(
                    attack(-5, RollMode.NORMAL, false, false, part("1d10 + 3", "fire")),
                    List.of(target(GOBLIN, 30, 0))).getFirst();

            assertEquals(Outcome.CRITICAL, outcome.outcome());
            assertEquals(List.of(new Resolver.Damage("fire", 14)), outcome.damage());
        }

        @Test
        @DisplayName("With a critical range of 19 (Improved Critical, PHB p.72) a 19 is a critical hit even below the AC, "
                + "and an 18 isn't")
        void critRange() {
            Effect champion = new Effect("Longsword", new Effect.Attack(0, RollMode.NORMAL, true, false, null,
                    List.of(), 19), List.of(part("1d8 + 3", "slashing")), null, List.of(), false, null);

            var nineteen = new Resolver(Rng.scripted(19, 4, 5)).resolve(champion, List.of(target(GOBLIN, 25, 0)))
                    .getFirst();
            var eighteen = new Resolver(Rng.scripted(18)).resolve(champion, List.of(target(GOBLIN, 25, 0)))
                    .getFirst();

            assertEquals(Outcome.CRITICAL, nineteen.outcome());
            assertEquals(List.of(new Resolver.Damage("slashing", 12)), nineteen.damage());
            assertEquals(Outcome.MISS, eighteen.outcome());
        }

        @Test
        @DisplayName("A damage part that rerolls 1s and 2s (Great Weapon Fighting, PHB p.72) rolls each such die once "
                + "more and keeps the new roll, a second 1 included")
        void rerollLowDice() {
            Effect greatsword = new Effect("Greatsword", new Effect.Attack(5, RollMode.NORMAL, true, false),
                    List.of(new Effect.DamagePart(Dice.parse("2d6 + 3"), "slashing", 2)), null, List.of(), false, null);

            var dice = Rng.scripted(15, 1, 1, 2, 6);
            var outcome = new Resolver(dice).resolve(greatsword, List.of(target(GOBLIN, 10, 0))).getFirst();

            // 1 is rolled again as 1 and kept; 2 is rolled again as 6
            assertEquals(List.of(new Resolver.Damage("slashing", 1 + 6 + 3)), outcome.damage());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("A natural 1 misses whatever the total (was: natural 1)")
        void naturalOne() {
            var outcome = new Resolver(Rng.scripted(1)).resolve(attack(20, RollMode.NORMAL, false, false,
                    part("1d10", "fire")), List.of(target(GOBLIN, 2, 0))).getFirst();

            assertEquals(Outcome.MISS, outcome.outcome());
            assertEquals(1, outcome.d20());
        }

        @Test
        @DisplayName("Advantage rolls two d20s and keeps the higher; disadvantage keeps the lower (was: advantage, disadvantage)")
        void advantageAndDisadvantage() {
            var advantage = new Resolver(Rng.scripted(5, 15, 7)).resolve(attack(5, RollMode.ADVANTAGE, false, false,
                    part("1d10", "fire")), List.of(target(GOBLIN, 15, 0))).getFirst();
            var disadvantage = new Resolver(Rng.scripted(15, 5)).resolve(attack(5, RollMode.DISADVANTAGE, false, false,
                    part("1d10", "fire")), List.of(target(GOBLIN, 15, 0))).getFirst();

            assertEquals(List.of(5, 15), advantage.d20s());
            assertEquals(15, advantage.d20());
            assertEquals(Outcome.HIT, advantage.outcome());
            assertEquals(5, disadvantage.d20());
            assertEquals(Outcome.MISS, disadvantage.outcome());
        }

        @Test
        @DisplayName("An unconscious target within 5 feet is attacked with advantage, and a hit is a critical hit (D161)")
        void unconsciousTargetWithinFiveFeet() {
            var outcome = new Resolver(Rng.scripted(3, 11, 4, 2)).resolve(attack(4, RollMode.NORMAL, true, false,
                    part("1d6", "slashing")), List.of(target(GOBLIN, 10, 0, "unconscious", "prone"))).getFirst();

            assertEquals(RollMode.ADVANTAGE, outcome.rollMode());
            assertEquals(List.of(3, 11), outcome.d20s());
            assertEquals(Outcome.CRITICAL, outcome.outcome());
            // The critical hit doubles the d6
            assertEquals(List.of(new Resolver.Damage("slashing", 6)), outcome.damage());
        }

        @Test
        @DisplayName("Beyond 5 feet an unconscious target is attacked normally, and a paralyzed one with advantage but no critical")
        void downedTargetBeyondFiveFeet() {
            var unconscious = new Resolver(Rng.scripted(15, 4)).resolve(attack(4, RollMode.NORMAL, false, false,
                    part("1d6", null)), List.of(target(GOBLIN, 10, 0, "unconscious"))).getFirst();
            var paralyzed = new Resolver(Rng.scripted(2, 15, 4)).resolve(attack(4, RollMode.NORMAL, false, false,
                    part("1d6", null)), List.of(target(GOBLIN, 10, 0, "paralyzed"))).getFirst();

            assertEquals(RollMode.NORMAL, unconscious.rollMode());
            assertEquals(Outcome.HIT, unconscious.outcome());
            assertEquals(RollMode.ADVANTAGE, paralyzed.rollMode());
            assertEquals(Outcome.HIT, paralyzed.outcome());
        }

        @Test
        @DisplayName("Attacks never hit automatically: an unconscious target can still be missed (D161)")
        void downedTargetsCanBeMissed() {
            var outcome = new Resolver(Rng.scripted(2, 3)).resolve(attack(0, RollMode.NORMAL, true, false,
                    part("1d6", null)), List.of(target(GOBLIN, 15, 0, "unconscious"))).getFirst();

            assertEquals(Outcome.MISS, outcome.outcome());
        }

        @Test
        @DisplayName("The DM's forced critical hits whatever the roll and doubles the dice (was CombatServiceTest 10.20)")
        void forcedCritical() {
            var outcome = new Resolver(Rng.scripted(1, 6, 6)).resolve(attack(0, RollMode.NORMAL, false, true,
                    part("1d8 + 3", "slashing")), List.of(target(GOBLIN, 30, 0))).getFirst();

            assertEquals(Outcome.CRITICAL, outcome.outcome());
            assertEquals(15, total(outcome.damage()));
        }

        @Test
        @DisplayName("Each target is its own attack roll with its own damage roll")
        void eachTargetIsItsOwnAttack() {
            var dice = Rng.scripted(15, 3, 4, 2);
            var outcomes = new Resolver(dice).resolve(attack(5, RollMode.NORMAL, false, false, part("1d6", "fire")),
                    List.of(target(GOBLIN, 12, 0), target(ORC, 12, 0)));

            assertEquals(3, total(outcomes.get(0).damage()));
            assertEquals(Outcome.MISS, outcomes.get(1).outcome());
            assertEquals(1, dice.remaining());
        }

        @Test
        @DisplayName("Every damage part rolls, each kept under its own type")
        void damagePartsKeepTheirTypes() {
            var outcome = new Resolver(Rng.scripted(15, 5, 3, 4)).resolve(attack(5, RollMode.NORMAL, false, false,
                    part("1d8 + 4", "slashing"), part("2d6", "psychic")), List.of(target(GOBLIN, 12, 0))).getFirst();

            assertEquals(List.of(new Resolver.Damage("slashing", 9), new Resolver.Damage("psychic", 7)), outcome.damage());
        }
    }

    @Nested
    @DisplayName("Saving throws")
    class Saves {

        @Test
        @DisplayName("A failed save takes the full damage and the conditions, and keeps the success outcome for Legendary Resistance")
        void failedSave() {
            var outcome = new Resolver(Rng.scripted(6, 6, 6, 6, 3)).resolve(
                    save(Ability.DEX, 15, true, List.of("prone"), part("4d6", "fire")),
                    List.of(target(GOBLIN, 12, 2))).getFirst();

            assertEquals(Outcome.SAVE_FAIL, outcome.outcome());
            assertEquals(5, outcome.total());
            assertEquals(24, total(outcome.damage()));
            assertEquals(List.of("prone"), outcome.conditions());
            assertEquals(List.of(new Resolver.Damage("fire", 12)), outcome.successDamage());
        }

        @Test
        @DisplayName("A successful save halves the damage, rounding down, and prevents the conditions (was: saves with halfOnSave)")
        void successfulSaveForHalf() {
            var outcome = new Resolver(Rng.scripted(6, 6, 6, 5, 18)).resolve(
                    save(Ability.DEX, 15, true, List.of("prone"), part("4d6", "fire")),
                    List.of(target(GOBLIN, 12, 2))).getFirst();

            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
            assertEquals(List.of(new Resolver.Damage("fire", 11)), outcome.damage());
            assertTrue(outcome.conditions().isEmpty());
            assertTrue(outcome.successDamage().isEmpty());
        }

        @Test
        @DisplayName("Without half on a save, a success prevents all of the damage (was: saves without halfOnSave)")
        void successfulSaveForNothing() {
            var outcome = new Resolver(Rng.scripted(4, 18)).resolve(
                    save(Ability.WIS, 15, false, List.of("paralyzed"), part("1d6", "psychic")),
                    List.of(target(GOBLIN, 12, -1))).getFirst();

            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
            assertTrue(outcome.damage().isEmpty());
        }

        @Test
        @DisplayName("Half of 1 is 0: halving has no minimum (D161)")
        void halvingHasNoMinimum() {
            var outcome = new Resolver(Rng.scripted(1, 20)).resolve(
                    save(Ability.DEX, 10, true, List.of(), part("1d4", "acid")),
                    List.of(target(GOBLIN, 12, 0))).getFirst();

            assertEquals(List.of(new Resolver.Damage("acid", 0)), outcome.damage());
        }

        @Test
        @DisplayName("Area damage is rolled once for the Effect, and each target saves on its own (was: multi-target resolution)")
        void areaDamageRolledOnce() {
            var dice = Rng.scripted(5, 5, 4, 4, 3, 3, 2, 2, 18, 3);
            var outcomes = new Resolver(dice).resolve(save(Ability.DEX, 15, true, List.of(), part("8d6", "fire")),
                    List.of(target(GOBLIN, 12, 2), target(ORC, 12, 1)));

            assertEquals(Outcome.SAVE_SUCCESS, outcomes.get(0).outcome());
            assertEquals(14, total(outcomes.get(0).damage()));
            assertEquals(Outcome.SAVE_FAIL, outcomes.get(1).outcome());
            assertEquals(28, total(outcomes.get(1).damage()));
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("STR and DEX saves fail automatically while unconscious, paralyzed, stunned or petrified, with no roll (D161)")
        void automaticFailures() {
            for (String condition : List.of("unconscious", "paralyzed", "stunned", "petrified")) {
                var outcome = new Resolver(Rng.scripted(6)).resolve(
                        save(Ability.DEX, 10, true, List.of(), part("1d6", "fire")),
                        List.of(target(GOBLIN, 12, 20, condition))).getFirst();

                assertEquals(Outcome.SAVE_FAIL, outcome.outcome(), condition);
                assertTrue(outcome.autoFailed(), condition);
                assertNull(outcome.d20(), condition);
                assertTrue(outcome.d20s().isEmpty(), condition);
            }
        }

        @Test
        @DisplayName("Other saves are rolled while unconscious")
        void otherSavesAreRolled() {
            var outcome = new Resolver(Rng.scripted(15)).resolve(save(Ability.WIS, 10, false, List.of(), new Effect.DamagePart[0]),
                    List.of(target(GOBLIN, 12, 0, "unconscious"))).getFirst();

            assertFalse(outcome.autoFailed());
            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
        }

        @Test
        @DisplayName("A save outside an Effect, such as a concentration check, succeeds on the DC or more")
        void standaloneSave() {
            var resolver = new Resolver(Rng.scripted(9, 8));

            var success = resolver.savingThrow(Ability.CON, 1, 10, List.of());
            var failure = resolver.savingThrow(Ability.CON, 1, 10, List.of());

            assertTrue(success.success());
            assertEquals(10, success.total());
            assertFalse(failure.success());
        }

        @Test
        @DisplayName("A dodging creature rolls DEX saves with advantage, keeping the higher d20 (PHB p.192)")
        void dodgingDexSave() {
            var outcome = new Resolver(Rng.scripted(6, 3, 14)).resolve(
                    save(Ability.DEX, 15, true, List.of(), part("1d6", "fire")),
                    List.of(target(GOBLIN, 12, 2, "dodging"))).getFirst();

            assertEquals(List.of(3, 14), outcome.d20s());
            assertEquals(14, outcome.d20());
            assertEquals(RollMode.ADVANTAGE, outcome.rollMode());
            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
        }

        @Test
        @DisplayName("A dodging creature's other saves are rolled once")
        void dodgingOtherSave() {
            var roll = new Resolver(Rng.scripted(9)).savingThrow(Ability.WIS, 0, 10, List.of("dodging"));

            assertEquals(new Resolver.SaveRoll(9, 9, false, false), roll);
        }
    }

    @Nested
    @DisplayName("Saves a hit calls for against its conditions")
    class HitSaves {

        @Test
        @DisplayName("A hit's failed save, rolled after the damage, gives the conditions it guards, and keeps all of "
                + "the damage as the success outcome for Legendary Resistance")
        void failedHitSave() {
            // 15 + 5 hits AC 12; 2d6 + 3 rolls 4 + 3 + 3; the STR save of 8 + 1 misses DC 13
            var dice = Rng.scripted(15, 4, 3, 8);
            var outcome = new Resolver(dice).resolve(hitWithSave(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    List.of("prone"), part("2d6 + 3", "piercing")), List.of(target(GOBLIN, 12, 1))).getFirst();

            assertEquals(Outcome.HIT, outcome.outcome());
            assertEquals(List.of(new Resolver.Damage("piercing", 10)), outcome.damage());
            assertEquals(new Resolver.SaveRoll(8, 9, false, false), outcome.hitSave());
            assertEquals(List.of("prone"), outcome.conditions());
            assertEquals(outcome.damage(), outcome.successDamage());
            assertFalse(outcome.autoFailed());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("A hit's successful save prevents only the conditions it guards; the damage and the other "
                + "conditions come with the hit")
        void successfulHitSave() {
            var outcome = new Resolver(Rng.scripted(15, 6, 14)).resolve(
                    hitWithSave(new Effect.HitSave(Ability.CON, 11, List.of("poisoned")),
                            List.of("grappled", "poisoned"), part("1d10", "piercing")),
                    List.of(target(GOBLIN, 12, 0))).getFirst();

            assertEquals(new Resolver.SaveRoll(14, 14, true, false), outcome.hitSave());
            assertEquals(List.of("grappled"), outcome.conditions());
            assertEquals(List.of(new Resolver.Damage("piercing", 6)), outcome.damage());
            assertTrue(outcome.successDamage().isEmpty());
        }

        @Test
        @DisplayName("A miss rolls no save and gives no conditions")
        void missRollsNoSave() {
            var dice = Rng.scripted(3);
            var outcome = new Resolver(dice).resolve(hitWithSave(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    List.of("prone"), part("2d6 + 3", "piercing")), List.of(target(GOBLIN, 12, 1))).getFirst();

            assertEquals(Outcome.MISS, outcome.outcome());
            assertNull(outcome.hitSave());
            assertTrue(outcome.conditions().isEmpty());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("A STR save a hit calls for fails automatically for a paralyzed target, with no roll (D161)")
        void automaticFailure() {
            // Paralyzed and within 5 feet: advantage, and the hit is a critical hit that doubles the d6
            var dice = Rng.scripted(9, 12, 2, 3);
            var outcome = new Resolver(dice).resolve(hitWithSave(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    List.of("prone"), part("1d6", "piercing")), List.of(target(GOBLIN, 12, 20, "paralyzed"))).getFirst();

            assertEquals(Outcome.CRITICAL, outcome.outcome());
            assertEquals(new Resolver.SaveRoll(null, null, false, true), outcome.hitSave());
            assertEquals(List.of("prone"), outcome.conditions());
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("The Effect's saving throw is its delivery's, or the one its attack's hit calls for")
        void saveAbility() {
            assertEquals(Ability.STR, hitWithSave(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    List.of("prone")).saveAbility());
            assertEquals(Ability.DEX, save(Ability.DEX, 15, true, List.of()).saveAbility());
            assertNull(attack(5, RollMode.NORMAL, true, false).saveAbility());
        }
    }

    @Nested
    @DisplayName("Automatic Effects and Effects on their user")
    class Automatic {

        @Test
        @DisplayName("An automatic Effect needs no roll, and its damage is rolled once for every target (was: auto-hit applies damage)")
        void automaticDamage() {
            var dice = Rng.scripted(2, 3, 3);
            Effect effect = new Effect("Magic Missile", new Effect.Automatic(), List.of(part("3d4 + 3", "force")), null,
                    List.of(), false, null);

            var outcomes = new Resolver(dice).resolve(effect, List.of(target(GOBLIN, 12, 0), target(ORC, 12, 0)));

            assertEquals(Outcome.AUTOMATIC, outcomes.get(0).outcome());
            assertNull(outcomes.get(0).d20());
            assertNull(outcomes.get(0).total());
            assertEquals(11, total(outcomes.get(0).damage()));
            assertEquals(11, total(outcomes.get(1).damage()));
        }

        @Test
        @DisplayName("An Effect on its user heals and gives its conditions (was: self-healing, self buff)")
        void self() {
            Effect effect = new Effect("Cure Wounds", new Effect.Self(), List.of(), Dice.parse("1d8 + 3"),
                    List.of("shielded"), false, null);

            var outcome = new Resolver(Rng.scripted(5)).resolve(effect, List.of(target(GOBLIN, 12, 0))).getFirst();

            assertEquals(Outcome.AUTOMATIC, outcome.outcome());
            assertEquals(8, outcome.healing());
            assertEquals(List.of("shielded"), outcome.conditions());
        }

        @Test
        @DisplayName("An automatic Effect without damage dice deals none (was: auto-hit with no damage dice)")
        void noDamage() {
            Effect effect = new Effect("Light", new Effect.Automatic(), List.of(), null, List.of(), false, null);

            var outcome = new Resolver(Rng.scripted()).resolve(effect, List.of(target(GOBLIN, 12, 0))).getFirst();

            assertTrue(outcome.damage().isEmpty());
            assertEquals(0, outcome.healing());
        }
    }

    @Nested
    @DisplayName("Metamagic (PHB p.102, M18)")
    class MetamagicOptions {

        @Test
        @DisplayName("Careful Spell: a protected creature succeeds without rolling and takes half of a half-on-save spell's damage; the other rolls")
        void carefulSucceedsAutomatically() {
            // 8d6 fire shared: 1+2+3+4+5+6+1+2 = 24; the orc rolls a 3
            var dice = Rng.scripted(1, 2, 3, 4, 5, 6, 1, 2, 3);
            var outcomes = new Resolver(dice).resolve(save(Ability.DEX, 15, true, List.of(), part("8d6", "fire")),
                    List.of(target(GOBLIN, 12, 0).succeedingAutomatically(), target(ORC, 13, 0)));

            assertEquals(Outcome.SAVE_SUCCESS, outcomes.get(0).outcome());
            assertNull(outcomes.get(0).d20());
            assertTrue(outcomes.get(0).d20s().isEmpty());
            assertEquals(12, total(outcomes.get(0).damage()));
            assertEquals(Outcome.SAVE_FAIL, outcomes.get(1).outcome());
            assertEquals(24, total(outcomes.get(1).damage()));
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("Careful Spell beats a condition that fails the save automatically, as a paralyzed creature's DEX save")
        void carefulBeatsAutomaticFailure() {
            var outcome = new Resolver(Rng.scripted(4)).resolve(
                    save(Ability.DEX, 15, false, List.of("restrained"), part("1d6", "fire")),
                    List.of(target(GOBLIN, 12, 0, "paralyzed").succeedingAutomatically())).getFirst();

            assertEquals(Outcome.SAVE_SUCCESS, outcome.outcome());
            assertTrue(outcome.conditions().isEmpty());
        }

        @Test
        @DisplayName("Heightened Spell: disadvantage on the save, the lower d20 counting")
        void heightenedDisadvantage() {
            var outcome = new Resolver(Rng.scripted(18, 6)).resolve(
                    save(Ability.WIS, 15, false, List.of("paralyzed")),
                    List.of(target(GOBLIN, 12, 2).withSaveSource(RollMode.DISADVANTAGE))).getFirst();

            assertEquals(Outcome.SAVE_FAIL, outcome.outcome());
            assertEquals(List.of(18, 6), outcome.d20s());
            assertEquals(6, outcome.d20());
            assertEquals(RollMode.DISADVANTAGE, outcome.rollMode());
        }

        @Test
        @DisplayName("Empowered Spell: rerolls up to N dice below their average, the furthest below first, keeping the new rolls")
        void empoweredRerollsLowestDice() {
            // 4d6: 1, 5, 3, 2; two rerolls go to the 1 and the 2 (3 is below 3.5 but less so), rolling 6 and 4
            var dice = Rng.scripted(1, 5, 3, 2, 6, 4);
            var outcome = new Resolver(dice).resolve(save(Ability.DEX, 15, true, List.of(), part("4d6", "fire")),
                    List.of(target(GOBLIN, 12, 0).succeedingAutomatically()), 2).getFirst();

            // 6 + 5 + 3 + 4 = 18, halved on the success
            assertEquals(9, total(outcome.damage()));
            assertEquals(0, dice.remaining());
        }

        @Test
        @DisplayName("Empowered Spell rerolls nothing when every die reached its average, and a hit's crit dice count too")
        void empoweredWithHighRollsAndCrit() {
            var high = Rng.scripted(15, 4, 6);
            var hit = new Resolver(high).resolve(attack(5, RollMode.NORMAL, false, false, part("2d6", "fire")),
                    List.of(target(GOBLIN, 12, 0)), 3).getFirst();
            assertEquals(10, total(hit.damage()));
            assertEquals(0, high.remaining());

            // A natural 20 doubles 1d10 to 2d10: 1 and 9, the 1 rerolled to 7
            var crit = Rng.scripted(20, 1, 9, 7);
            var critical = new Resolver(crit).resolve(attack(5, RollMode.NORMAL, false, false, part("1d10", "fire")),
                    List.of(target(GOBLIN, 12, 0)), 1).getFirst();
            assertEquals(Outcome.CRITICAL, critical.outcome());
            assertEquals(16, total(critical.damage()));
            assertEquals(0, crit.remaining());
        }
    }
}
