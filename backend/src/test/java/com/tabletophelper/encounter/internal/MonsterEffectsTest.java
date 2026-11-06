package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.monster.MonsterTemplatesTest;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading monster action templates into Effects (D160). The old MonsterActionResolverEngine had no tests of
 * its own; these cover what it read, and every DAMAGE part, which it cut to the first. The multiattack cases read
 * the seeded data of the Adult Red Dragon (MM), Tashlyn Yafeera, Orcus, the Cult Fanatic and the Scion of Thrym
 * (D165).
 */
class MonsterEffectsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CombatFixture.ScriptedDice dice = new CombatFixture.ScriptedDice();
    private final MonsterEffects monsterEffects = new MonsterEffects(dice);
    private final UUID aria = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();
    private final UUID cass = UUID.randomUUID();

    private MonsterTemplates templates(String json) {
        return MonsterTemplates.parse(json, "The templates");
    }

    // The action, read as the only one in a monster's actions
    private MonsterTemplates.Action action(String json) {
        return templates("{\"actions\": [" + json + "]}").actions().getFirst();
    }

    private MonsterEffects.MonsterAction plan(String action, List<UUID> targets) {
        return monsterEffects.plan(action(action), MonsterTemplates.NONE, targets, MonsterEffects.Overrides.NONE);
    }

    private MonsterEffects.MonsterAction plan(String action, List<UUID> targets, MonsterEffects.Overrides overrides) {
        return monsterEffects.plan(action(action), MonsterTemplates.NONE, targets, overrides);
    }

    @Test
    @DisplayName("Every DAMAGE part counts, each with its own damage type")
    void everyDamagePart() {
        var plan = plan("""
                {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 8, "attackType": "MELEE_WEAPON",
                 "effects": [{"effectType": "DAMAGE", "damageDice": "2d10 + 6", "damageType": "piercing"},
                             {"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "Fire"}]}""", List.of(aria));

        Effect effect = plan.steps().getFirst().effect();
        assertEquals(List.of(new Effect.DamagePart(Dice.parse("2d10+6"), "piercing"),
                new Effect.DamagePart(Dice.parse("2d6"), "fire")), effect.damage());
        assertEquals(new Effect.Attack(8, RollMode.NORMAL, true, false), effect.delivery());
        assertEquals(List.of(aria), plan.steps().getFirst().targetIds());
    }

    @Test
    @DisplayName("A ranged attack counts as beyond 5 feet; a flat amount, as an octopus deals, is damage too")
    void rangedAttackAndFlatDamage() {
        var plan = plan("""
                {"name": "Spit", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "attackType": "RANGED_WEAPON",
                 "effects": [{"damageDice": "1", "damageType": "acid", "effectType": "DAMAGE", "flatDamage": true}]}""",
                List.of(aria));

        Effect effect = plan.steps().getFirst().effect();
        assertFalse(((Effect.Attack) effect.delivery()).withinFiveFeet());
        assertEquals(List.of(new Effect.DamagePart(new Dice(0, 0, 1), "acid")), effect.damage());
    }

    @Test
    @DisplayName("A saving throw reads its ability, DC and half on a success, and its conditions")
    void savingThrow() {
        var plan = plan("""
                {"name": "Fire Breath", "deliveryMethod": "SAVING_THROW", "saveDC": 21, "saveAbility": "DEX",
                 "halfOnSave": true, "effects": [{"effectType": "DAMAGE", "damageDice": "18d6", "damageType": "fire"},
                 {"effectType": "CONDITION", "condition": "Frightened"}]}""", List.of(aria, bram));

        Effect effect = plan.steps().getFirst().effect();
        assertEquals(new Effect.Save(Ability.DEX, 21, true), effect.delivery());
        assertEquals(List.of("frightened"), effect.conditions());
        assertEquals(List.of(aria, bram), plan.steps().getFirst().targetIds());
    }

    @Test
    @DisplayName("A condition keeps its rounds and its Repeat Save, whose DC and ability default to the action's (D162), and a grapple its escape DC")
    void conditionEnds() {
        var plan = plan("""
                {"name": "Chilling Gaze", "deliveryMethod": "SAVING_THROW", "saveDC": 18, "saveAbility": "CON",
                 "effects": [{"effectType": "CONDITION", "condition": "paralyzed", "durationRounds": 10,
                              "saveToEndEachTurn": true},
                             {"effectType": "CONDITION", "condition": "frightened", "durationRounds": 10,
                              "saveToEndEachTurn": true, "saveToEndDC": 15, "saveToEndAbility": "WIS"},
                             {"effectType": "CONDITION", "condition": "stunned", "durationRounds": 1},
                             {"effectType": "CONDITION", "condition": "grappled", "escapeDC": 14}]}""", List.of(aria));

        Effect effect = plan.steps().getFirst().effect();
        assertEquals(new Effect.ConditionEnd(10, new Effect.RepeatSave(Ability.CON, 18)),
                effect.conditionEnds().get("paralyzed"));
        assertEquals(new Effect.ConditionEnd(10, new Effect.RepeatSave(Ability.WIS, 15)),
                effect.conditionEnds().get("frightened"));
        assertEquals(new Effect.ConditionEnd(1, null), effect.conditionEnds().get("stunned"));
        // A grapple's escape DC is shown, never rolled against
        assertEquals(new Effect.ConditionEnd(null, null, 14), effect.conditionEnds().get("grappled"));
    }

    @Test
    @DisplayName("The DM's override of the DC is the Repeat Save's DC too")
    void overrideRepeatSaveDc() {
        var plan = plan("""
                {"name": "Gaze", "deliveryMethod": "SAVING_THROW", "saveDC": 12, "saveAbility": "WIS",
                 "effects": [{"effectType": "CONDITION", "condition": "charmed", "saveToEndEachTurn": true,
                              "saveToEndDC": 12}]}""", List.of(aria), new MonsterEffects.Overrides(null, 16, null));

        assertEquals(new Effect.RepeatSave(Ability.WIS, 16),
                plan.steps().getFirst().effect().conditionEnds().get("charmed").repeatSave());
    }

    @Test
    @DisplayName("The DM's override replaces the template's attack bonus or DC")
    void overrides() {
        var attack = plan("""
                {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                 "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "slashing"}]}""",
                List.of(aria), new MonsterEffects.Overrides(9, null, true));
        var save = plan("""
                {"name": "Gaze", "deliveryMethod": "SAVING_THROW", "saveDC": 12, "saveAbility": "WIS",
                 "effects": [{"effectType": "CONDITION", "condition": "charmed"}]}""",
                List.of(aria), new MonsterEffects.Overrides(null, 16, null));

        assertEquals(new Effect.Attack(9, RollMode.ADVANTAGE, true, false), attack.steps().getFirst().effect().delivery());
        assertEquals(16, ((Effect.Save) save.steps().getFirst().effect().delivery()).dc());
    }

    @Test
    @DisplayName("AUTO_DAMAGE applies its damage without its conditions, and AUTO_HIT with them")
    void automaticDeliveries() {
        String effects = "[{\"effectType\": \"DAMAGE\", \"damageDice\": \"2d6\", \"damageType\": \"poison\"},"
                + " {\"effectType\": \"CONDITION\", \"condition\": \"poisoned\"}]";

        var autoDamage = plan("{\"name\": \"Aura\", \"deliveryMethod\": \"AUTO_DAMAGE\", \"effects\": " + effects + "}",
                List.of(aria));
        var autoHit = plan("{\"name\": \"Cloud\", \"effects\": " + effects + "}", List.of(aria));

        assertTrue(autoDamage.steps().getFirst().effect().conditions().isEmpty());
        assertInstanceOf(Effect.Automatic.class, autoHit.steps().getFirst().effect().delivery());
        assertEquals(List.of("poisoned"), autoHit.steps().getFirst().effect().conditions());
    }

    @Test
    @DisplayName("Actions the template leaves to the DM come back with the reason and no steps")
    void manualActions() {
        assertEquals("Change Shape description", plan("""
                {"name": "Change Shape", "automatable": false, "description": "Change Shape description"}""",
                List.of()).manualReason());
        assertEquals("Delivery method 'NONE' requires DM adjudication", plan("""
                {"name": "Frightful Presence", "deliveryMethod": "NONE",
                 "effects": [{"effectType": "CONDITION", "condition": "frightened"}]}""", List.of()).manualReason());
        assertEquals("Action contains BUFF effect type requiring DM adjudication", plan("""
                {"name": "Roar", "effects": [{"effectType": "BUFF", "description": "Allies gain advantage"}]}""",
                List.of()).manualReason());
        assertEquals("Bad's damage dice can't be rolled automatically", plan("""
                {"name": "Bad", "deliveryMethod": "ATTACK_ROLL",
                 "effects": [{"effectType": "DAMAGE", "damageDice": "lots", "damageType": "fire"}]}""",
                List.of()).manualReason());
    }

    @Test
    @DisplayName("Healing is left to the DM, with a note, and the rest of the action still resolves")
    void healingIsLeftToTheDm() {
        var plan = plan("""
                {"name": "Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                 "effects": [{"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "necrotic"},
                             {"effectType": "HEAL", "healingDice": "1d8"}]}""", List.of(aria));

        assertTrue(plan.automated());
        assertEquals(List.of("Drain's healing is left to the DM."), plan.notes());
        assertNull(plan.steps().getFirst().effect().healing());
    }

    @Test
    @DisplayName("Eye Rays fire rayCount rays chosen by the dice, each a save against the next target in turn")
    void eyeRays() {
        dice.script(1, 1);
        var plan = plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rayCount": 2, "rays": [
                  {"name": "Charm Ray", "saveDC": 16, "saveAbility": "WIS",
                   "effects": [{"effectType": "CONDITION", "condition": "charmed"}]},
                  {"name": "Paralyzing Ray", "saveDC": 16, "saveAbility": "CON",
                   "effects": [{"effectType": "CONDITION", "condition": "paralyzed"}]},
                  {"name": "Death Ray", "saveDC": 16, "halfOnSave": true,
                   "effects": [{"effectType": "DAMAGE", "damageDice": "10d10", "damageType": "necrotic"}]}]}""",
                List.of(aria, bram));

        // Shuffling with 1, 1 swaps the last ray to the front, then swaps the first two
        assertEquals(List.of("Paralyzing Ray", "Death Ray"),
                plan.steps().stream().map(MonsterEffects.Step::label).toList());
        assertEquals(new Effect.Save(Ability.CON, 16, false), plan.steps().get(0).effect().delivery());
        // A ray that names no ability is a DEX save
        assertEquals(new Effect.Save(Ability.DEX, 16, true), plan.steps().get(1).effect().delivery());
        assertEquals(List.of(aria), plan.steps().get(0).targetIds());
        assertEquals(List.of(bram), plan.steps().get(1).targetIds());
    }

    @Test
    @DisplayName("Eye Rays without targets or rays are left to the DM")
    void eyeRaysWithoutTargets() {
        assertEquals("No valid targets for eye rays", plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rays": [{"name": "Ray"}]}""", List.of()).manualReason());
        assertEquals("Eye Rays has no rays defined", plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rays": []}""", List.of(aria)).manualReason());
    }

    @Nested
    @DisplayName("Saves an attack's hit calls for against its conditions")
    class HitSaves {

        // The Dire Wolf's bite as seeded (MM): a DC 13 Strength save recorded on its prone
        private static final String DIRE_WOLF_BITE = """
                {"name": "Bite", "reach": 5, "effects": [
                   {"damageDice": "2d6 + 3", "damageType": "piercing", "effectType": "DAMAGE"},
                   {"saveDC": 13, "condition": "prone", "effectType": "CONDITION", "saveAbility": "STR"}],
                 "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                 "deliveryMethod": "ATTACK_ROLL"}""";

        @Test
        @DisplayName("The Dire Wolf's bite knocks a creature prone only on a failed DC 13 Strength save, which its "
                + "data records on the condition")
        void saveOnTheCondition() {
            Effect effect = plan(DIRE_WOLF_BITE, List.of(aria)).steps().getFirst().effect();

            assertEquals(new Effect.Attack(5, RollMode.NORMAL, true, false,
                    new Effect.HitSave(Ability.STR, 13, List.of("prone"))), effect.delivery());
            assertEquals(List.of("prone"), effect.conditions());
            assertEquals(Ability.STR, effect.saveAbility());
        }

        @Test
        @DisplayName("A save the data records on the action guards a condition that records none, as the "
                + "Chupacabra's bite does")
        void saveOnTheAction() {
            Effect effect = plan("""
                    {"name": "Bite", "reach": 5, "saveDC": 13, "effects": [
                       {"damageDice": "2d4 + 2", "damageType": "piercing", "effectType": "DAMAGE"},
                       {"condition": "prone", "effectType": "CONDITION"}],
                     "actionType": "ACTION", "attackType": "MELEE_WEAPON", "halfOnSave": false, "attackBonus": 4,
                     "saveAbility": "STR", "targetCount": 1, "deliveryMethod": "ATTACK_ROLL"}""", List.of(aria))
                    .steps().getFirst().effect();

            assertEquals(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    ((Effect.Attack) effect.delivery()).hitSave());
        }

        @Test
        @DisplayName("A condition with no save, or a save without an ability, comes with the hit: the Grell's "
                + "grapple, and the Rug of Smothering's blinded")
        void conditionsThatComeWithTheHit() {
            Effect grell = plan("""
                    {"name": "Tentacle", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "effects": [
                       {"damageDice": "1d10 + 2", "damageType": "piercing", "effectType": "DAMAGE"},
                       {"saveDC": 11, "condition": "poisoned", "effectType": "CONDITION", "saveAbility": "CON"},
                       {"escapeDC": 15, "condition": "grappled", "effectType": "CONDITION"}]}""", List.of(aria))
                    .steps().getFirst().effect();
            Effect rug = plan("""
                    {"name": "Smother", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5, "effects": [
                       {"escapeDC": 13, "condition": "grappled", "effectType": "CONDITION"},
                       {"saveDC": 13, "condition": "blinded", "effectType": "CONDITION", "saveAbility": null}]}""",
                    List.of(aria)).steps().getFirst().effect();

            assertEquals(new Effect.HitSave(Ability.CON, 11, List.of("poisoned")),
                    ((Effect.Attack) grell.delivery()).hitSave());
            assertEquals(List.of("poisoned", "grappled"), grell.conditions());
            assertNull(((Effect.Attack) rug.delivery()).hitSave());
            assertEquals(List.of("grappled", "blinded"), rug.conditions());
        }

        @Test
        @DisplayName("The Mind Flayer's tentacles grapple on the hit and stun only on a failed DC 15 Intelligence save, "
                + "which its data keeps apart from saveDC (MM)")
        void mindFlayerGrapplesAndStunsOnAFailedSave() {
            Effect effect = plan("""
                    {"name": "Tentacle", "reach": 5, "effects": [
                       {"damageDice": "2d10+4", "damageType": "psychic", "effectType": "DAMAGE"},
                       {"escapeDC": 15, "condition": "grappled", "effectType": "CONDITION", "durationText": "until escape",
                        "durationRounds": null},
                       {"condition": "stunned", "effectType": "CONDITION"}],
                     "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 7, "targetCount": 1,
                     "deliveryMethod": "ATTACK_ROLL", "secondarySaveDC": 15, "secondarySaveAbility": "INT"}""",
                    List.of(aria)).steps().getFirst().effect();

            assertEquals(new Effect.HitSave(Ability.INT, 15, List.of("stunned")),
                    ((Effect.Attack) effect.delivery()).hitSave());
            assertEquals(List.of("grappled", "stunned"), effect.conditions());
        }

        @Test
        @DisplayName("A save recorded on the action never guards a grapple, as the Mind Flayer Prophet's doesn't, but one "
                + "recorded on the grapple itself does, as the Mindwitness's")
        void grappleOnlyBehindItsOwnSave() {
            Effect prophet = plan("""
                    {"name": "Tentacle", "reach": 5, "saveDC": 16, "effects": [
                       {"damageDice": "2d10 + 5", "damageType": "psychic", "effectType": "DAMAGE"},
                       {"condition": "grappled", "effectType": "CONDITION"},
                       {"condition": "stunned", "effectType": "CONDITION"}],
                     "actionType": "ACTION", "attackType": "MELEE_WEAPON", "halfOnSave": false, "attackBonus": 8,
                     "saveAbility": "INT", "deliveryMethod": "ATTACK_ROLL"}""", List.of(aria)).steps().getFirst().effect();
            Effect mindwitness = plan("""
                    {"name": "Tentacle", "reach": 10, "effects": [
                       {"damageDice": "4d8 + 2", "damageType": "psychic", "effectType": "DAMAGE"},
                       {"saveDC": 13, "condition": "grappled", "effectType": "CONDITION", "saveAbility": "INT"},
                       {"saveDC": 13, "condition": "restrained", "effectType": "CONDITION", "saveAbility": "INT"}],
                     "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                     "deliveryMethod": "ATTACK_ROLL"}""", List.of(aria)).steps().getFirst().effect();

            assertEquals(new Effect.HitSave(Ability.INT, 16, List.of("stunned")),
                    ((Effect.Attack) prophet.delivery()).hitSave());
            assertEquals(new Effect.HitSave(Ability.INT, 13, List.of("grappled", "restrained")),
                    ((Effect.Attack) mindwitness.delivery()).hitSave());
        }

        @Test
        @DisplayName("The DM's override of the DC is the hit's save's DC too")
        void overrideHitSaveDc() {
            Effect effect = plan(DIRE_WOLF_BITE, List.of(aria), new MonsterEffects.Overrides(null, 15, null))
                    .steps().getFirst().effect();

            assertEquals(new Effect.HitSave(Ability.STR, 15, List.of("prone")),
                    ((Effect.Attack) effect.delivery()).hitSave());
        }

        @Test
        @DisplayName("Conditions that record different saves are left to the DM, since a hit makes one save")
        void differentSaves() {
            var plan = plan("""
                    {"name": "Sting", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5, "effects": [
                       {"saveDC": 11, "condition": "poisoned", "effectType": "CONDITION", "saveAbility": "CON"},
                       {"saveDC": 13, "condition": "prone", "effectType": "CONDITION", "saveAbility": "STR"}]}""",
                    List.of(aria));

            assertFalse(plan.automated());
            assertEquals("Sting's conditions record different saving throws", plan.manualReason());
        }

        @Test
        @DisplayName("A condition's Repeat Save defaults to the save it records, as the Ghoul's paralysis does (D162)")
        void repeatSaveDefaultsToTheConditionsSave() {
            Effect effect = plan("""
                    {"name": "Claw", "reach": 5, "effects": [
                       {"damageDice": "2d4 + 2", "damageType": "slashing", "effectType": "DAMAGE"},
                       {"saveDC": 10, "condition": "paralyzed", "effectType": "CONDITION", "saveAbility": "CON",
                        "durationText": "1 minute", "durationRounds": 10, "saveToEndEachTurn": true}],
                     "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 4, "targetCount": 1,
                     "deliveryMethod": "ATTACK_ROLL"}""", List.of(aria)).steps().getFirst().effect();

            assertEquals(new Effect.ConditionEnd(10, new Effect.RepeatSave(Ability.CON, 10)),
                    effect.conditionEnds().get("paralyzed"));
            assertEquals(new Effect.HitSave(Ability.CON, 10, List.of("paralyzed")),
                    ((Effect.Attack) effect.delivery()).hitSave());
        }

        @Test
        @DisplayName("A multiattack's attack keeps its hit's save")
        void multiattackComponent() {
            MonsterTemplates wolf = templates("{\"actions\": [{\"name\": \"Multiattack\", \"isMultiattack\": true, "
                    + "\"multiattackComponents\": [\"Bite\", \"Bite\"]}, " + DIRE_WOLF_BITE + "]}");

            MonsterEffects.Multiattack multiattack = monsterEffects.multiattack(wolf.actions().getFirst(), wolf,
                    MonsterEffects.Overrides.NONE);

            assertEquals(new Effect.HitSave(Ability.STR, 13, List.of("prone")),
                    ((Effect.Attack) multiattack.components().getFirst().effect().delivery()).hitSave());
        }
    }

    @Nested
    @DisplayName("Multiattack (D165)")
    class Multiattacks {

        private final MonsterTemplates dragon = templates(MonsterTemplatesTest.ADULT_RED_DRAGON);

        private MonsterEffects.Multiattack read(MonsterTemplates monster) {
            MonsterTemplates.Action multiattack = monster.actions().stream()
                    .filter(MonsterTemplates.Action::multiattack).findFirst().orElseThrow();
            return monsterEffects.multiattack(multiattack, monster, MonsterEffects.Overrides.NONE);
        }

        @Test
        @DisplayName("The Adult Red Dragon's components are its Frightful Presence, which takes any number of "
                + "targets, then a bite and two claws, one target each")
        void adultRedDragon() {
            MonsterEffects.Multiattack multiattack = read(dragon);

            assertTrue(multiattack.readable());
            assertEquals("The dragon can use its Frightful Presence. It then makes three attacks: one with its bite "
                    + "and two with its claws.", multiattack.description());
            assertEquals(List.of("Frightful Presence", "Bite", "Claw", "Claw"),
                    multiattack.components().stream().map(MonsterEffects.MultiattackComponent::name).toList());
            assertEquals(java.util.Arrays.asList(null, 1, 1, 1),
                    multiattack.components().stream().map(MonsterEffects.MultiattackComponent::maxTargets).toList());
            assertTrue(multiattack.components().stream().allMatch(MonsterEffects.MultiattackComponent::automated));
            assertEquals(new Effect.Save(Ability.WIS, 19, false),
                    multiattack.components().getFirst().effect().delivery());
            assertEquals(new Effect.Attack(14, RollMode.NORMAL, true, false),
                    multiattack.components().get(1).effect().delivery());
        }

        @Test
        @DisplayName("Each component goes to the targets chosen for it, an empty list skipping it")
        void targetsPerComponent() {
            var plan = monsterEffects.multiattack(read(dragon),
                    List.of(List.of(aria, bram), List.of(aria), List.of(), List.of(cass)));

            assertTrue(plan.automated());
            assertEquals(List.of("Frightful Presence", "Bite", "Claw"),
                    plan.steps().stream().map(MonsterEffects.Step::label).toList());
            assertEquals(List.of(List.of(aria, bram), List.of(aria), List.of(cass)),
                    plan.steps().stream().map(MonsterEffects.Step::targetIds).toList());
        }

        @Test
        @DisplayName("Without targets per component, every component goes to the same targets, as before")
        void sameTargets() {
            MonsterTemplates.Action multiattack = dragon.actions().getFirst();

            var plan = monsterEffects.plan(multiattack, dragon, List.of(aria), MonsterEffects.Overrides.NONE);

            assertEquals(4, plan.steps().size());
            assertTrue(plan.steps().stream().allMatch(step -> step.targetIds().equals(List.of(aria))));
        }

        @Test
        @DisplayName("Tashlyn's choice of three greatsword or three shortbow attacks reads as the components listed")
        void optionsReadAsListed() {
            MonsterTemplates tashlyn = templates("""
                    {"actions": [
                      {"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true,
                       "multiattackOptions": [{"name": "Greatsword", "count": 3}, {"name": "Shortbow", "count": 3}],
                       "multiattackComponents": ["Greatsword", "Greatsword", "Greatsword"],
                       "multiattackDescription": "Tashlyn makes three attacks with her greatsword or her shortbow."},
                      {"name": "Greatsword", "reach": 5, "effects": [{"damageDice": "2d6 + 4", "damageType": "slashing",
                        "effectType": "DAMAGE"}], "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 8,
                       "targetCount": 1, "deliveryMethod": "ATTACK_ROLL"}]}""");

            MonsterEffects.Multiattack multiattack = read(tashlyn);

            assertTrue(multiattack.readable());
            assertEquals(3, multiattack.components().size());
        }

        @Test
        @DisplayName("Without components, with one naming no action, as Orcus's fragment does, or with an and/or "
                + "pattern the data leaves to review, a multiattack is the DM's")
        void unreadable() {
            MonsterTemplates fanatic = templates("""
                    {"actions": [{"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true,
                      "multiattackComponents": [], "multiattackDescription": "The fanatic makes two melee attacks."}]}""");
            MonsterTemplates orcus = templates("""
                    {"actions": [{"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true,
                      "multiattackComponents": ["Of Orcus"], "multiattackDescription": "Orcus makes two Wand of Orcus attacks.",
                      "hasUnresolvedComponents": true}]}""");
            MonsterTemplates scion = templates("""
                    {"actions": [{"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true,
                      "_multiattackNote": " REVIEW: Complex multiattack with and/or pattern",
                      "multiattackComponents": ["Glacier Throw", "Ice Axe", "Slam"],
                      "multiattackDescription": "The scion makes one Ice Axe and two Slam attacks, or it makes two Glacier Throw attacks."},
                      {"name": "Slam", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 17,
                       "effects": [{"damageDice": "4d10 + 10", "damageType": "force", "effectType": "DAMAGE"}]}]}""");
            MonsterTemplates unnamed = templates("""
                    {"actions": [{"name": "Multiattack", "isMultiattack": true, "multiattackComponents": ["Bite", "Wing"]},
                      {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                       "effects": [{"damageDice": "1d6", "damageType": "piercing", "effectType": "DAMAGE"}]}]}""");

            assertEquals("Multiattack has no components defined", read(fanatic).unreadable());
            assertEquals("Multiattack names Of Orcus, which isn't one of its actions", read(orcus).unreadable());
            assertEquals("Multiattack's attacks can't be read from its stat block", read(scion).unreadable());
            assertEquals("Multiattack names Wing, which isn't one of its actions", read(unnamed).unreadable());
            assertTrue(read(unnamed).components().isEmpty());
            assertEquals("Orcus makes two Wand of Orcus attacks.", read(orcus).description());

            var plan = monsterEffects.plan(orcus.actions().getFirst(), orcus, List.of(aria),
                    MonsterEffects.Overrides.NONE);
            assertFalse(plan.automated());
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("A component the DM resolves takes no targets and is noted once; the overrides reach every component")
        void manualComponentAndOverrides() {
            MonsterTemplates monster = templates("""
                    {"actions": [{"name": "Multiattack", "isMultiattack": true,
                       "multiattackComponents": ["Pacify", "Claw", "Claw"]},
                      {"name": "Pacify", "automatable": false, "description": "The psurlon calms a creature."},
                      {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                       "effects": [{"damageDice": "1d6", "damageType": "slashing", "effectType": "DAMAGE"},
                                   {"effectType": "HEAL", "healingDice": "1d4"}]}]}""");
            MonsterEffects.Multiattack multiattack = monsterEffects.multiattack(monster.actions().getFirst(), monster,
                    new MonsterEffects.Overrides(9, null, null));

            var plan = monsterEffects.multiattack(multiattack, List.of(List.of(aria), List.of(aria), List.of(bram)));

            assertFalse(multiattack.components().getFirst().automated());
            assertNull(multiattack.components().getFirst().maxTargets());
            assertEquals(List.of("Claw", "Claw"), plan.steps().stream().map(MonsterEffects.Step::label).toList());
            assertEquals(new Effect.Attack(9, RollMode.NORMAL, true, false), plan.steps().getFirst().effect().delivery());
            assertEquals(List.of("Pacify: The psurlon calms a creature.", "Claw's healing is left to the DM."),
                    plan.notes());
        }
    }
}
