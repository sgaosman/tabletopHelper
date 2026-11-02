package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Reading spell templates into Effects (D160). From the old SpellResolverEngineTest: the manual resolution
 * fallback, cantrip scaling by level (player levels 1, 4, 5, 11 and 17, a CR 21 monster and a fractional CR),
 * upcast scaling (base, one and three levels up) and the concentration tracking nests. Its attack, save,
 * automatic, self and multi-target cases went to ResolverTest and SpellcastingServiceTest, the silence check
 * and descriptions to SpellcastingServiceTest, and getSaveModifier to CreatureStatsTest.
 */
class SpellEffectsTest {

    private static final String FIRE_BOLT = """
            {"spellName": "Fire Bolt", "spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "requiresManualResolution": false, "concentration": false,
             "components": {"verbal": true, "somatic": true},
             "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "fire",
               "cantripScaling": {"5": "2d10", "11": "3d10", "17": "4d10"}}],
             "conditionsInflicted": null}""";

    private static final String GUIDING_BOLT = """
            {"spellName": "Guiding Bolt", "spellLevel": 1, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
             "requiresManualResolution": false, "concentration": false,
             "effects": [{"effectType": "DAMAGE", "damageDice": "4d6", "damageType": "radiant",
               "upcastScaling": {"additionalDicePerLevel": "1d6"}}],
             "conditionsInflicted": null}""";

    private CombatFixture f;
    private EncounterParticipant wizard;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        wizard = f.player("Wizard", 30, 12);
    }

    private SpellEffects.SpellPlan cast(String spell, int slotLevel, EncounterParticipant caster, int attackBonus) {
        return f.spellEffects.forCast(spell, slotLevel, caster, attackBonus, 13, null);
    }

    private static List<Dice> dice(Effect effect) {
        return effect.damage().stream().map(Effect.DamagePart::dice).toList();
    }

    @Nested
    @DisplayName("Manual resolution fallback")
    class ManualResolution {

        @Test
        @DisplayName("A spell that isn't found is left to the DM")
        void spellNotFound() {
            when(f.spellRepository.findByNameIgnoreCase("Unknown Spell")).thenReturn(Optional.empty());

            var plan = cast("Unknown Spell", 0, wizard, 5);

            assertFalse(plan.automated());
            assertEquals("No effect template available", plan.manualReason());
        }

        @Test
        @DisplayName("A spell without an effect template is left to the DM")
        void noEffectTemplate() {
            f.spell("Wish", 9, null);

            assertFalse(cast("Wish", 9, wizard, 5).automated());
        }

        @Test
        @DisplayName("A spell its template marks for manual resolution is left to the DM, keeping its concentration")
        void requiresManualResolution() {
            f.spell("Wall of Force", 5, """
                    {"spellName": "Wall of Force", "spellLevel": 5, "deliveryMethod": "NONE",
                     "requiresManualResolution": true, "manualResolutionReason": "Complex area effect",
                     "concentration": true, "durationRounds": 100, "effects": [], "conditionsInflicted": null}""");

            var plan = cast("Wall of Force", 5, wizard, 5);

            assertFalse(plan.automated());
            assertEquals("Complex area effect", plan.manualReason());
            assertTrue(plan.concentration());
            assertEquals(100, plan.durationRounds());
        }

        @Test
        @DisplayName("Dice the app can't roll leave the spell to the DM instead of failing the cast")
        void unreadableDice() {
            f.spell("Goodberry", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                     "healing": {"healingDice": "1 per berry (10 berries total)"}, "conditionsInflicted": null}""");

            var plan = cast("Goodberry", 1, wizard, 5);

            assertFalse(plan.automated());
            assertEquals("Goodberry's dice can't be rolled automatically", plan.manualReason());
        }
    }

    @Nested
    @DisplayName("Cantrip scaling by character level")
    class CantripScaling {

        @ParameterizedTest(name = "Level {0} gets {1}")
        @CsvSource({"1, 1d10", "4, 1d10", "5, 2d10", "11, 3d10", "17, 4d10"})
        @DisplayName("A player's cantrip dice follow its level")
        void playerLevels(int level, String expected) {
            wizard.getCharacter().setLevel(level);
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            assertEquals(List.of(Dice.parse(expected)), dice(cast("Fire Bolt", 0, wizard, 5).effect()));
        }

        @Test
        @DisplayName("A monster's cantrips scale with its challenge rating as its level")
        void monsterChallengeRating() {
            EncounterParticipant lich = f.monster("Lich", 135, 17);
            lich.getMonster().setChallengeRating("21");
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            assertEquals(List.of(Dice.parse("4d10")), dice(cast("Fire Bolt", 0, lich, 12).effect()));
        }

        @Test
        @DisplayName("A monster with a fractional challenge rating counts as level 1")
        void fractionalChallengeRating() {
            EncounterParticipant imp = f.monster("Imp", 10, 13);
            imp.getMonster().setChallengeRating("1/4");
            f.spell("Fire Bolt", 0, FIRE_BOLT);

            assertEquals(List.of(Dice.parse("1d10")), dice(cast("Fire Bolt", 0, imp, 5).effect()));
        }
    }

    @Nested
    @DisplayName("Upcast scaling")
    class UpcastScaling {

        @ParameterizedTest(name = "Slot {0} rolls {1}")
        @CsvSource({"1, 4d6", "2, 5d6", "4, 7d6", "0, 4d6"})
        @DisplayName("Each slot level above the spell's adds its upcast dice")
        void upcastDice(int slotLevel, String expected) {
            f.spell("Guiding Bolt", 1, GUIDING_BOLT);

            assertEquals(List.of(Dice.parse(expected)), dice(cast("Guiding Bolt", slotLevel, wizard, 5).effect()));
        }

        @Test
        @DisplayName("A scaling interval counts every so many levels, as Spiritual Weapon's every two")
        void scalingInterval() {
            f.spell("Flame Blade", 2, """
                    {"spellLevel": 2, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "3d6", "damageType": "fire",
                       "upcastScaling": {"scalingInterval": 2, "additionalDicePerLevel": "1d6"}}]}""");

            assertEquals(List.of(Dice.parse("3d6")), dice(cast("Flame Blade", 3, wizard, 5).effect()));
            assertEquals(List.of(Dice.parse("4d6")), dice(cast("Flame Blade", 4, wizard, 5).effect()));
        }

        @Test
        @DisplayName("Upcast dice of a die the spell doesn't roll yet join it as a group of their own")
        void upcastDiceOfAnotherDie() {
            f.spell("Chaos Bolt", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d8+1d6", "damageType": "force",
                       "upcastScaling": {"additionalDicePerLevel": "1d6"}}]}""");

            assertEquals(List.of(Dice.parse("2d8"), Dice.parse("1d6")), dice(cast("Chaos Bolt", 1, wizard, 5).effect()));
            assertEquals(List.of(Dice.parse("2d8"), Dice.parse("3d6")), dice(cast("Chaos Bolt", 3, wizard, 5).effect()));
        }
    }

    @Nested
    @DisplayName("The MOD placeholder")
    class SpellcastingModifier {

        @Test
        @DisplayName("MOD becomes the spellcasting modifier as a number: the attack bonus less proficiency")
        void modifierAsANumber() {
            f.spell("Cure Wounds", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                     "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""");

            // A level 1 caster with +5 to hit has +2 proficiency and +3 from its ability
            assertEquals(new Dice(1, 8, 3), cast("Cure Wounds", 1, wizard, 5).effect().healing());
            assertEquals(new Dice(3, 8, 3), cast("Cure Wounds", 3, wizard, 5).effect().healing());
        }

        @Test
        @DisplayName("A negative modifier still reads, which text substitution couldn't manage")
        void negativeModifier() {
            f.spell("Magic Stone", 0, """
                    {"spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "1d6+SPELL_MOD", "damageType": "bludgeoning"}]}""");

            assertEquals(List.of(new Dice(1, 6, -1)), dice(cast("Magic Stone", 0, wizard, 1).effect()));
        }
    }

    @Nested
    @DisplayName("Delivery")
    class Delivery {

        @Test
        @DisplayName("A melee spell attack counts as within 5 feet and a ranged one as not, with the caster's advantage")
        void spellAttacks() {
            f.spell("Fire Bolt", 0, FIRE_BOLT);
            f.spell("Shocking Grasp", 0, """
                    {"spellLevel": 0, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "MELEE",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "lightning"}]}""");

            var ranged = (Effect.Attack) f.spellEffects.forCast("Fire Bolt", 0, wizard, 7, 15, true).effect().delivery();
            var melee = (Effect.Attack) f.spellEffects.forCast("Shocking Grasp", 0, wizard, 7, 15, false).effect().delivery();

            assertEquals(new Effect.Attack(7, RollMode.ADVANTAGE, false, false), ranged);
            assertEquals(new Effect.Attack(7, RollMode.DISADVANTAGE, true, false), melee);
        }

        @Test
        @DisplayName("A saving throw reads its ability, the caster's DC and whether a success halves the damage")
        void savingThrow() {
            f.spell("Fireball", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
                       "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""");

            Effect effect = f.spellEffects.forCast("Fireball", 3, wizard, 7, 15, null).effect();

            assertEquals(new Effect.Save(Ability.DEX, 15, true), effect.delivery());
            assertEquals(List.of(new Effect.DamagePart(Dice.parse("8d6"), "fire")), effect.damage());
        }

        @Test
        @DisplayName("A spell on its caster heals and gives conditions, and deals none of its template's damage")
        void selfIgnoresDamage() {
            f.spell("Armor of Agathys", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SELF",
                     "effects": [{"effectType": "DAMAGE", "damageDice": "5", "damageType": "cold"}],
                     "conditionsInflicted": ["warded"]}""");

            Effect effect = cast("Armor of Agathys", 1, wizard, 5).effect();

            assertInstanceOf(Effect.Self.class, effect.delivery());
            assertTrue(effect.damage().isEmpty());
            assertEquals(List.of("warded"), effect.conditions());
        }

        @Test
        @DisplayName("A delivery of NONE is left to the DM")
        void noneIsManual() {
            f.spell("Sleep", 1, """
                    {"spellLevel": 1, "deliveryMethod": "NONE", "concentration": false, "effects": []}""");

            assertEquals("Delivery method 'NONE' requires DM adjudication",
                    cast("Sleep", 1, wizard, 5).manualReason());
        }
    }

    @Nested
    @DisplayName("Concentration tracking in the plan")
    class ConcentrationTracking {

        @Test
        @DisplayName("A concentration spell's plan says so, with its duration")
        void concentrationSpell() {
            f.spell("Haste", 3, """
                    {"spellName": "Haste", "spellLevel": 3, "deliveryMethod": "SELF", "concentration": true,
                     "durationRounds": 10, "components": {"verbal": true}, "effects": [],
                     "conditionsInflicted": ["hasted"]}""");

            var plan = cast("Haste", 3, wizard, 5);

            assertTrue(plan.concentration());
            assertEquals(10, plan.durationRounds());
            assertTrue(plan.verbal());
            assertEquals(10, plan.effect().durationRounds());
        }

        @Test
        @DisplayName("Any other spell's plan needs no concentration")
        void otherSpell() {
            f.spell("Guiding Bolt", 1, GUIDING_BOLT);

            assertFalse(cast("Guiding Bolt", 1, wizard, 5).concentration());
        }
    }

    @Nested
    @DisplayName("Repeat effects and limits")
    class RepeatAndLimits {

        @Test
        @DisplayName("repeatEffect true repeats the spell's own effect, upcast")
        void repeatTheSpellsEffect() {
            f.spell("Moonbeam", 2, """
                    {"spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "concentration": true, "repeatEffect": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "radiant",
                       "upcastScaling": {"additionalDicePerLevel": "1d10"}}]}""");

            var plan = f.spellEffects.forRepeat("Moonbeam", 3, wizard, 5, 14, null);

            assertEquals(new Effect.Save(Ability.CON, 14, true), plan.effect().delivery());
            assertEquals(List.of(Dice.parse("3d10")), dice(plan.effect()));
            assertFalse(plan.concentration());
        }

        @Test
        @DisplayName("A repeat effect object replaces the delivery and dice")
        void repeatObject() {
            f.spell("Witch Bolt", 1, """
                    {"spellLevel": 1, "deliveryMethod": "SPELL_ATTACK", "spellAttackType": "RANGED",
                     "repeatEffect": {"damageDice": "1d12", "damageType": "lightning", "deliveryMethod": "AUTO_HIT",
                       "usesUpcastScaling": false},
                     "effects": [{"effectType": "DAMAGE", "damageDice": "1d12", "damageType": "lightning",
                       "upcastScaling": {"additionalDicePerLevel": "1d12"}}]}""");

            var plan = f.spellEffects.forRepeat("Witch Bolt", 3, wizard, 5, 14, null);

            assertInstanceOf(Effect.Automatic.class, plan.effect().delivery());
            assertEquals(List.of(new Effect.DamagePart(Dice.parse("1d12"), "lightning")), plan.effect().damage());
        }

        @Test
        @DisplayName("A repeat effect of healing alone needs no roll")
        void repeatHealing() {
            f.spell("Aura of Vitality", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SELF",
                     "repeatEffect": {"healingDice": "2d6", "deliveryMethod": "AUTO_HIT", "usesUpcastScaling": false},
                     "effects": []}""");

            var plan = f.spellEffects.forRepeat("Aura of Vitality", 3, wizard, 5, 14, null);

            assertInstanceOf(Effect.Automatic.class, plan.effect().delivery());
            assertEquals(Dice.parse("2d6"), plan.effect().healing());
        }

        @Test
        @DisplayName("A spell without a repeat effect is left to the DM")
        void noRepeatEffect() {
            f.spell("Guiding Bolt", 1, GUIDING_BOLT);

            assertEquals("Spell has no repeatable effect",
                    f.spellEffects.forRepeat("Guiding Bolt", 1, wizard, 5, 14, null).manualReason());
        }

        @Test
        @DisplayName("The target limit counts the template's targets, plus any per level above the spell's")
        void maxTargets() {
            f.spell("Hold Person", 2, """
                    {"spellLevel": 2, "targetCount": 1, "targetCountUpcastScaling": {"additionalTargetsPerLevel": 1}}""");
            f.spell("Fireball", 3, "{\"spellLevel\": 3, \"targetCount\": null}");

            assertEquals(OptionalInt.of(1), f.spellEffects.maxTargets("Hold Person", 2));
            assertEquals(OptionalInt.of(3), f.spellEffects.maxTargets("Hold Person", 4));
            assertEquals(OptionalInt.empty(), f.spellEffects.maxTargets("Fireball", 3));
        }
    }

    @Nested
    @DisplayName("Condition end rules (D162)")
    class ConditionEnds {

        @Test
        @DisplayName("Conditions listed as objects are read by name, with their own rounds and Repeat Saves")
        void conditionObjects() {
            f.spell("Hold Person", 2, """
                    {"spellName": "Hold Person", "spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                     "concentration": true, "durationRounds": 10, "effects": [],
                     "conditionsInflicted": [{"condition": "Paralyzed", "saveToAvoid": "WIS", "durationRounds": 10,
                       "saveToEndEachTurn": true, "saveToEndAbility": "WIS"}]}""");
            f.spell("Ray of Sickness", 1, """
                    {"spellName": "Ray of Sickness", "spellLevel": 1, "deliveryMethod": "SAVING_THROW",
                     "saveAbility": "CON", "concentration": false, "durationRounds": null, "effects": [],
                     "conditionsInflicted": [{"condition": "poisoned", "durationRounds": 1}]}""");

            Effect hold = cast("Hold Person", 2, wizard, 5).effect();
            Effect ray = cast("Ray of Sickness", 1, wizard, 5).effect();

            assertEquals(List.of("paralyzed"), hold.conditions());
            assertEquals(new Effect.ConditionEnd(10, new Effect.RepeatSave(Ability.WIS, 13)),
                    hold.conditionEnds().get("paralyzed"));
            assertEquals(List.of("poisoned"), ray.conditions());
            assertEquals(new Effect.ConditionEnd(1, null), ray.conditionEnds().get("poisoned"));
        }

        @Test
        @DisplayName("A Repeat Save without its own ability uses the spell's, and a conditional one is left to the DM")
        void repeatSaveAbilities() {
            f.spell("Blindness", 2, """
                    {"spellName": "Blindness", "spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON",
                     "concentration": false, "durationRounds": 10, "effects": [],
                     "conditionsInflicted": [{"condition": "blinded", "saveToEndEachTurn": true}]}""");
            f.spell("Fear", 3, """
                    {"spellName": "Fear", "spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "WIS",
                     "concentration": true, "durationRounds": 10, "effects": [],
                     "conditionsInflicted": [{"condition": "frightened", "durationRounds": 10,
                       "saveToEndEachTurn": true, "saveToEndAbility": "WIS",
                       "saveToEndCondition": "creature cannot see caster"}]}""");

            assertEquals(new Effect.ConditionEnd(null, new Effect.RepeatSave(Ability.CON, 13)),
                    cast("Blindness", 2, wizard, 5).effect().conditionEnds().get("blinded"));
            assertEquals(new Effect.ConditionEnd(10, null),
                    cast("Fear", 3, wizard, 5).effect().conditionEnds().get("frightened"));
        }
    }
}
