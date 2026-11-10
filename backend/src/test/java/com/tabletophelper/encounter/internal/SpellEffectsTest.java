package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellTemplate;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SpellTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

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
        return f.spellEffects.forCast(spell, slotLevel, caster, null, attackBonus, 13, null);
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
        @DisplayName("MOD becomes the spellcasting modifier as a number: with no ability known, the attack bonus less proficiency")
        void modifierAsANumber() {
            f.spell("Cure Wounds", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                     "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""");

            // A level 1 caster with +5 to hit has +2 proficiency and +3 from its ability
            assertEquals(new Dice(1, 8, 3), cast("Cure Wounds", 1, wizard, 5).effect().healing());
            assertEquals(new Dice(3, 8, 3), cast("Cure Wounds", 3, wizard, 5).effect().healing());
        }

        @Test
        @DisplayName("With the casting ability known, MOD is that ability score's modifier, whatever the attack bonus (D164)")
        void modifierFromTheAbilityScore() {
            f.spell("Cure Wounds", 1, """
                    {"spellLevel": 1, "deliveryMethod": "AUTO_HIT", "effects": [],
                     "healing": {"healingDice": "1d8+MOD", "upcastScaling": {"additionalDicePerLevel": "1d8"}}}""");
            wizard.getCharacter().setWisdom(16);

            // +9 less +2 proficiency would give +7; WIS 16 gives +3
            assertEquals(new Dice(1, 8, 3),
                    f.spellEffects.forCast("Cure Wounds", 1, wizard, "WIS", 9, 13, null).effect().healing());
            assertEquals(new Dice(1, 8, 3),
                    f.spellEffects.forCast("Cure Wounds", 1, wizard, "Wisdom", 9, 13, null).effect().healing());
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

            var ranged = (Effect.Attack) f.spellEffects.forCast("Fire Bolt", 0, wizard, null, 7, 15, true).effect().delivery();
            var melee = (Effect.Attack) f.spellEffects.forCast("Shocking Grasp", 0, wizard, null, 7, 15, false).effect().delivery();

            assertEquals(new Effect.Attack(7, RollMode.ADVANTAGE, false, false).withWeapon(false), ranged);
            assertEquals(new Effect.Attack(7, RollMode.DISADVANTAGE, true, false).withWeapon(false), melee);
        }

        @Test
        @DisplayName("A saving throw reads its ability, the caster's DC and whether a success halves the damage")
        void savingThrow() {
            f.spell("Fireball", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "halfOnSave": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "8d6", "damageType": "fire",
                       "upcastScaling": {"additionalDicePerLevel": "1d6"}}], "conditionsInflicted": null}""");

            Effect effect = f.spellEffects.forCast("Fireball", 3, wizard, null, 7, 15, null).effect();

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
        @DisplayName("Using an effect again costs the template's repeatActionType, and an action when it doesn't say "
                + "(D174)")
        void repeatCost() {
            f.spell("Spiritual Weapon", 2, """
                    {"spellLevel": 2, "repeatEffect": true, "repeatActionType": "BONUS_ACTION"}""");
            f.spell("Spirit Guardians", 3, """
                    {"spellLevel": 3, "repeatEffect": true, "repeatActionType": "NONE"}""");
            f.spell("Witch Bolt", 1, """
                    {"spellLevel": 1, "repeatEffect": true, "repeatActionType": "ACTION"}""");
            f.spell("Old Moonbeam", 2, """
                    {"spellLevel": 2, "repeatEffect": true}""");
            when(f.spellRepository.findByNameIgnoreCase("Unknown Spell")).thenReturn(Optional.empty());

            assertEquals(ActionCost.BONUS_ACTION, f.spellEffects.repeatCost("Spiritual Weapon"));
            assertEquals(ActionCost.NONE, f.spellEffects.repeatCost("Spirit Guardians"));
            assertEquals(ActionCost.ACTION, f.spellEffects.repeatCost("Witch Bolt"));
            assertEquals(ActionCost.ACTION, f.spellEffects.repeatCost("Old Moonbeam"));
            assertEquals(ActionCost.ACTION, f.spellEffects.repeatCost("Unknown Spell"));
        }

        @Test
        @DisplayName("A repeatActionType other than ACTION, BONUS_ACTION or NONE makes the template unreadable")
        void unknownRepeatCost() {
            assertNotNull(SpellTemplate.parse("""
                    {"repeatEffect": true, "repeatActionType": "REACTION"}""").unreadable());
            assertNotNull(SpellTemplate.parse("""
                    {"repeatEffect": true, "repeatActionType": "bonus action"}""").unreadable());
            assertNull(SpellTemplate.parse("""
                    {"repeatEffect": true, "repeatActionType": "NONE"}""").unreadable());
        }

        @Test
        @DisplayName("The bundled data's 68 lasting spells each leave a Spell Effect its caster directs at the cost "
                + "the spell's text gives, or whose area acts by itself at the moments it gives, or both (M15)")
        void bundledLastingSpells() throws IOException {
            Map<String, ActionCost> costs = new LinkedHashMap<>();
            Map<String, List<SpellTrigger>> triggers = new LinkedHashMap<>();
            Map<String, Boolean> moves = new LinkedHashMap<>();
            Set<String> endOnSave = new java.util.HashSet<>();
            try (InputStream in = getClass().getResourceAsStream(
                    "/data/spell-effects/spell-effect-definitions.json")) {
                assertNotNull(in, "the spell effect templates are on the classpath");
                for (JsonNode definition : JsonMapper.builder().build().readTree(in)) {
                    SpellTemplate template = SpellTemplate.of(definition);
                    String name = definition.path("spellName").asString();
                    assertNull(template.unreadable(), name);
                    if (!template.lasting()) {
                        continue;
                    }
                    if (template.repeatEffect() != null) {
                        costs.put(name, template.repeatActionType());
                        moves.put(name, !template.resolvesWhenDirected());
                    }
                    triggers.put(name, template.effectTriggers().stream()
                            .flatMap(trigger -> trigger.when().stream()).toList());
                    if (template.effectTriggers().stream().anyMatch(SpellTemplate.EffectTrigger::endsSpellOnSave)) {
                        endOnSave.add(name);
                    }
                }
            }
            // Only Searing Smite's and Phantasmal Killer's texts end the spell on a successful save (PHB p.274, p.265);
            // Ensnaring Strike's creature escapes with a Strength check as an action instead (PHB p.237); Immolation's
            // burning creature's success ends it too (XGE p.158, D187)
            assertEquals(Set.of("Searing Smite", "Phantasmal Killer", "Immolation"), endOnSave);

            Map<String, ActionCost> expected = new LinkedHashMap<>();
            // PHB p.289, p.220, p.261, p.285, p.242; XGE p.161
            for (String spell : List.of("Witch Bolt", "Call Lightning", "Moonbeam", "Vampiric Touch", "Flame Blade",
                    "Maximilian's Earthen Grasp")) {
                expected.put(spell, ActionCost.ACTION);
            }
            // PHB p.278, p.242, p.250, p.216; XGE p.161, p.154
            for (String spell : List.of("Spiritual Weapon", "Flaming Sphere", "Heat Metal", "Melf's Minute Meteors",
                    "Dust Devil", "Aura of Vitality")) {
                expected.put(spell, ActionCost.BONUS_ACTION);
            }
            // The phantasm deals its damage on the caster's turn, needing no action (PHB p.264)
            expected.put("Phantasmal Force", ActionCost.NONE);
            // 4th level (D185): Grasping Vine's lash and Storm Sphere's bolt are bonus actions (PHB p.246, XGE p.166),
            // Watery Sphere moves with an action (XGE p.170), and the Faithful Hound bites on its own (PHB p.261)
            expected.put("Grasping Vine", ActionCost.BONUS_ACTION);
            expected.put("Storm Sphere", ActionCost.BONUS_ACTION);
            expected.put("Watery Sphere", ActionCost.ACTION);
            expected.put("Mordenkainen's Faithful Hound", ActionCost.NONE);
            // 5th level (D187): Enervation's drain, Telekinesis and the beam of Wall of Light take an action (XGE p.155,
            // PHB p.280, XGE p.170); Bigby's Hand, Dawn's move, Wrath of Nature's rocks, Holy Weapon's burst and Far
            // Step are bonus actions (PHB p.218, XGE p.153, p.171, p.157, p.155); Geas's damage needs none (PHB p.244)
            for (String spell : List.of("Enervation", "Telekinesis", "Wall of Light")) {
                expected.put(spell, ActionCost.ACTION);
            }
            for (String spell : List.of("Bigby's Hand", "Dawn", "Wrath of Nature", "Holy Weapon", "Far Step")) {
                expected.put(spell, ActionCost.BONUS_ACTION);
            }
            expected.put("Geas", ActionCost.NONE);
            // 7th level (D189): Whirlwind's move takes an action (XGE p.171); Crown of Stars' motes, Mordenkainen's
            // Sword and Draconic Transformation's breath are bonus actions (XGE p.152, PHB p.262, FTD p.19)
            expected.put("Whirlwind", ActionCost.ACTION);
            for (String spell : List.of("Crown of Stars", "Mordenkainen's Sword", "Draconic Transformation")) {
                expected.put(spell, ActionCost.BONUS_ACTION);
            }
            // 6th level (D188): Sunbeam's new line, Eyebite's new target and the Investitures' attacks take an action
            // (PHB p.279, p.238; XGE p.159-160), Soul Cage's Steal Life a bonus action (XGE p.165), and Mental
            // Prison's burst happens when the creature escapes (XGE p.161)
            for (String spell : List.of("Sunbeam", "Eyebite", "Investiture of Flame", "Investiture of Ice",
                    "Investiture of Stone", "Investiture of Wind")) {
                expected.put(spell, ActionCost.ACTION);
            }
            expected.put("Soul Cage", ActionCost.BONUS_ACTION);
            expected.put("Mental Prison", ActionCost.NONE);
            // 8th and 9th level (D190): Illusory Dragon's breath and Blade of Disaster's attacks are bonus actions (XGE
            // p.157, TCE p.106); Earthquake's prone save at the end of the caster's turns needs none (PHB p.236). Dark
            // Star, Incendiary Cloud, Maddening Darkness, Ravenous Void and Weird act by themselves
            expected.put("Illusory Dragon", ActionCost.BONUS_ACTION);
            expected.put("Blade of Disaster", ActionCost.BONUS_ACTION);
            expected.put("Earthquake", ActionCost.NONE);
            // Prismatic Wall's use is a creature passing through its layers, which costs its caster nothing (PHB p.267,
            // D195)
            expected.put("Prismatic Wall", ActionCost.NONE);
            // Storm of Vengeance's and Tsunami's uses deal their round's damage at no cost (PHB p.279, p.284, D195)
            expected.put("Storm of Vengeance", ActionCost.NONE);
            expected.put("Tsunami", ActionCost.NONE);
            assertEquals(68, triggers.size());
            assertEquals(List.of(SpellTrigger.END_OF_TURN), triggers.get("Weird"));
            assertEquals(List.of(SpellTrigger.ENTER, SpellTrigger.START_OF_TURN), triggers.get("Ravenous Void"));
            assertEquals(expected, Map.copyOf(costs));
            // Only Moonbeam's, Dust Devil's and Dawn's uses move them without resolving anything (PHB p.261, XGE p.154,
            // p.153)
            assertEquals(Set.of("Moonbeam", "Dust Devil", "Dawn"), moves.entrySet().stream()
                    .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toSet()));

            List<SpellTrigger> enterOrStart = List.of(SpellTrigger.ENTER, SpellTrigger.START_OF_TURN);
            assertEquals(enterOrStart, triggers.get("Moonbeam"));
            assertEquals(enterOrStart, triggers.get("Spirit Guardians"));
            assertEquals(enterOrStart, triggers.get("Cloud of Daggers"));
            assertEquals(List.of(SpellTrigger.ENTER, SpellTrigger.END_OF_TURN), triggers.get("Create Bonfire"));
            assertEquals(List.of(SpellTrigger.END_OF_TURN), triggers.get("Flaming Sphere"));
            assertEquals(List.of(SpellTrigger.END_OF_TURN), triggers.get("Dust Devil"));
            assertEquals(List.of(SpellTrigger.START_OF_TURN, SpellTrigger.END_OF_TURN), triggers.get("Hunger of Hadar"));
            for (String spell : List.of("Searing Smite", "Ensnaring Strike", "Tasha's Caustic Brew")) {
                assertEquals(List.of(SpellTrigger.START_OF_TURN), triggers.get(spell), spell);
            }
            assertEquals(List.of(SpellTrigger.ENTER, SpellTrigger.END_OF_TURN), triggers.get("Wall of Fire"));
            assertEquals(enterOrStart, triggers.get("Evard's Black Tentacles"));
            assertEquals(enterOrStart, triggers.get("Sickening Radiance"));
            assertEquals(List.of(SpellTrigger.ENTER), triggers.get("Guardian of Faith"));
            assertEquals(List.of(SpellTrigger.END_OF_TURN), triggers.get("Phantasmal Killer"));
            // Each lasting spell can be used somehow
            triggers.forEach((spell, moments) -> assertTrue(costs.containsKey(spell) || !moments.isEmpty(), spell));
        }

        @Test
        @DisplayName("Hunger of Hadar's two triggers: 2d6 cold at the start of a turn, no save, and a DEX save "
                + "against 2d6 acid at its end (PHB p.251)")
        void hungerOfHadar() {
            f.spell("Hunger of Hadar", 3, """
                    {"spellLevel": 3, "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX", "concentration": true,
                     "effectTriggers": [
                       {"when": ["START_OF_TURN"], "deliveryMethod": "AUTO_HIT", "damageDice": "2d6", "damageType": "cold"},
                       {"when": ["END_OF_TURN"], "deliveryMethod": "SAVING_THROW", "saveAbility": "DEX",
                        "halfOnSave": false, "damageDice": "2d6", "damageType": "acid"}],
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "cold"}]}""");

            Effect start = f.spellEffects.forTrigger("Hunger of Hadar", SpellTrigger.START_OF_TURN, 5, wizard,
                    null, 5, 13).effect();
            Effect end = f.spellEffects.forTrigger("Hunger of Hadar", SpellTrigger.END_OF_TURN, 5, wizard,
                    null, 5, 13).effect();

            assertInstanceOf(Effect.Automatic.class, start.delivery());
            assertEquals("cold", start.damage().getFirst().type());
            assertEquals(new Effect.Save(Ability.DEX, 13, false), end.delivery());
            assertEquals("acid", end.damage().getFirst().type());
            assertTrue(end.conditions().isEmpty());
            assertFalse(f.spellEffects.forTrigger("Hunger of Hadar", SpellTrigger.ENTER, 5, wizard, null, 5, 13)
                    .automated());
        }

        @Test
        @DisplayName("A trigger moment other than ENTER, START_OF_TURN or END_OF_TURN makes the template unreadable")
        void unknownTrigger() {
            assertNotNull(SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["LEAVE"]}]}""").unreadable());
            assertNotNull(SpellTemplate.parse("""
                    {"effectTriggers": [{"damageDice": "1d6"}]}""").unreadable());
            assertNull(SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["ENTER"]}]}""").unreadable());
        }

        @Test
        @DisplayName("A trigger's endsSpellOnSave is read as true or false, false when left out; anything else makes "
                + "the template unreadable")
        void endsSpellOnSave() {
            SpellTemplate ends = SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["START_OF_TURN"], "endsSpellOnSave": true}]}""");
            SpellTemplate goesOn = SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["START_OF_TURN"]}]}""");

            assertTrue(ends.effectTriggers().getFirst().endsSpellOnSave());
            assertFalse(goesOn.effectTriggers().getFirst().endsSpellOnSave());
            assertNotNull(SpellTemplate.parse("""
                    {"effectTriggers": [{"when": ["START_OF_TURN"], "endsSpellOnSave": "yes"}]}""").unreadable());
        }

        @Test
        @DisplayName("repeatEffect true repeats the spell's own effect, upcast")
        void repeatTheSpellsEffect() {
            f.spell("Moonbeam", 2, """
                    {"spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                     "concentration": true, "repeatEffect": true,
                     "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "radiant",
                       "upcastScaling": {"additionalDicePerLevel": "1d10"}}]}""");

            var plan = f.spellEffects.forRepeat("Moonbeam", 3, wizard, null, 5, 14, null);

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

            var plan = f.spellEffects.forRepeat("Witch Bolt", 3, wizard, null, 5, 14, null);

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

            var plan = f.spellEffects.forRepeat("Aura of Vitality", 3, wizard, null, 5, 14, null);

            assertInstanceOf(Effect.Automatic.class, plan.effect().delivery());
            assertEquals(Dice.parse("2d6"), plan.effect().healing());
        }

        @Test
        @DisplayName("A spell without a repeat effect is left to the DM")
        void noRepeatEffect() {
            f.spell("Guiding Bolt", 1, GUIDING_BOLT);

            assertEquals("Spell has no repeatable effect",
                    f.spellEffects.forRepeat("Guiding Bolt", 1, wizard, null, 5, 14, null).manualReason());
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

    @Nested
    @DisplayName("Ongoing Modifiers (D184)")
    class Modifiers {

        private static final String MAGIC_WEAPON = """
                {"spellLevel": 2, "deliveryMethod": "AUTO_HIT", "concentration": true, "durationRounds": 600,
                 "effects": [{"effectType": "BUFF", "description": "+1",
                   "modifiers": [{"kind": "ATTACK_ROLL", "flat": 1, "weaponOnly": true,
                                  "upcast": {"every": 2, "flat": 1}},
                                 {"kind": "HIT_DAMAGE", "flat": 1, "weaponOnly": true,
                                  "upcast": {"every": 2, "flat": 1}}]}]}""";

        private static final String ELEMENTAL_WEAPON = """
                {"spellLevel": 3, "deliveryMethod": "AUTO_HIT", "concentration": true,
                 "castOptions": ["acid", "cold", "fire", "lightning", "thunder"],
                 "effects": [{"effectType": "BUFF", "description": "+1 and 1d4",
                   "modifiers": [{"kind": "HIT_DAMAGE", "dice": "1d4", "damageTypes": ["OPTION"], "weaponOnly": true,
                                  "upcast": {"every": 2, "dice": "1d4"}}]}]}""";

        @Test
        @DisplayName("Magic Weapon is +1 at 2nd level, +2 at 4th and +3 at 6th (PHB p.257)")
        void magicWeaponUpcast() {
            f.spell("Magic Weapon", 2, MAGIC_WEAPON);
            assertEquals(1, f.spellEffects.modifiers("Magic Weapon", 2, null, 3, null).modifiers().getFirst().flat());
            assertEquals(2, f.spellEffects.modifiers("Magic Weapon", 4, null, 3, null).modifiers().getFirst().flat());
            assertEquals(3, f.spellEffects.modifiers("Magic Weapon", 6, null, 3, null).modifiers().getFirst().flat());
        }

        @Test
        @DisplayName("Elemental Weapon deals the chosen type, 2d4 at 5th level (PHB p.237), and needs its option")
        void elementalWeapon() {
            f.spell("Elemental Weapon", 3, ELEMENTAL_WEAPON);
            var modifier = f.spellEffects.modifiers("Elemental Weapon", 5, "fire", 3, null).modifiers().getFirst();
            assertEquals("2d4", modifier.dice());
            assertEquals(List.of("fire"), modifier.damageTypes());
            assertEquals("cold", f.spellEffects.option("Elemental Weapon", "COLD"));
            assertThrows(com.tabletophelper.shared.ValidationException.class,
                    () -> f.spellEffects.option("Elemental Weapon", null));
            assertThrows(com.tabletophelper.shared.ValidationException.class,
                    () -> f.spellEffects.option("Elemental Weapon", "radiant"));
            f.spell("Bless", 1, "{\"spellLevel\": 1, \"effects\": []}");
            assertThrows(com.tabletophelper.shared.ValidationException.class,
                    () -> f.spellEffects.option("Bless", "fire"));
        }

        @Test
        @DisplayName("A modifier of an unknown kind makes the template unreadable, so the content load fails")
        void unknownKind() {
            var template = SpellTemplate.parse("""
                    {"spellLevel": 1, "effects": [{"effectType": "BUFF", "modifiers": [{"kind": "LUCK"}]}]}""");
            assertTrue(template.unreadable() != null);
        }

        @Test
        @DisplayName("Durations as the spells data words them, in rounds of 6 seconds (PHB p.189)")
        void durations() {
            assertEquals(10, SpellEffects.durationRounds("Concentration, up to 1 minute"));
            assertEquals(600, SpellEffects.durationRounds("Concentration, up to 1 hour"));
            assertEquals(4800, SpellEffects.durationRounds("8 hours"));
            assertEquals(1, SpellEffects.durationRounds("1 round"));
            assertEquals(14400, SpellEffects.durationRounds("1 day"));
            assertEquals(null, SpellEffects.durationRounds("Special"));
            assertEquals(null, SpellEffects.durationRounds(null));
        }

        @Test
        @DisplayName("A concentration spell without a template still needs concentration, for its duration when it can be read")
        void untemplatedConcentration() {
            Spell spell = f.spell("Hold Monster", 5, null);
            spell.setConcentration(true);
            spell.setDuration("Concentration, up to 1 minute");

            var plan = cast("Hold Monster", 5, wizard, 5);
            assertFalse(plan.automated());
            assertTrue(plan.concentration());
            assertEquals(10, plan.durationRounds());
        }
    }
}
