package com.tabletophelper.encounter.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.combat.Effect;
import com.tabletophelper.rules.combat.RollMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading monster action templates into Effects (D160). The old MonsterActionResolverEngine had no tests of
 * its own; these cover what it read, and every DAMAGE part, which it cut to the first.
 */
class MonsterEffectsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CombatFixture.ScriptedDice dice = new CombatFixture.ScriptedDice();
    private final MonsterEffects monsterEffects = new MonsterEffects(dice);
    private final UUID aria = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    private JsonNode json(String text) throws Exception {
        return objectMapper.readTree(text);
    }

    private MonsterEffects.MonsterAction plan(String action, String templates, List<UUID> targets) throws Exception {
        return monsterEffects.plan(json(action), json(templates), targets, null, null, null);
    }

    @Test
    @DisplayName("Every DAMAGE part counts, each with its own damage type")
    void everyDamagePart() throws Exception {
        var plan = plan("""
                {"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 8, "attackType": "MELEE_WEAPON",
                 "effects": [{"effectType": "DAMAGE", "damageDice": "2d10 + 6", "damageType": "piercing"},
                             {"effectType": "DAMAGE", "damageDice": "2d6", "damageType": "Fire"}]}""", "{}", List.of(aria));

        Effect effect = plan.steps().getFirst().effect();
        assertEquals(List.of(new Effect.DamagePart(Dice.parse("2d10+6"), "piercing"),
                new Effect.DamagePart(Dice.parse("2d6"), "fire")), effect.damage());
        assertEquals(new Effect.Attack(8, RollMode.NORMAL, true, false), effect.delivery());
        assertEquals(List.of(aria), plan.steps().getFirst().targetIds());
    }

    @Test
    @DisplayName("A ranged attack counts as beyond 5 feet; a flat amount is damage too")
    void rangedAttackAndFlatDamage() throws Exception {
        var plan = plan("""
                {"name": "Spit", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4, "attackType": "RANGED_WEAPON",
                 "effects": [{"effectType": "DAMAGE", "flatDamage": 1, "damageType": "acid"}]}""", "{}", List.of(aria));

        Effect effect = plan.steps().getFirst().effect();
        assertFalse(((Effect.Attack) effect.delivery()).withinFiveFeet());
        assertEquals(List.of(new Effect.DamagePart(new Dice(0, 0, 1), "acid")), effect.damage());
    }

    @Test
    @DisplayName("A saving throw reads its ability, DC and half on a success, and its conditions")
    void savingThrow() throws Exception {
        var plan = plan("""
                {"name": "Fire Breath", "deliveryMethod": "SAVING_THROW", "saveDC": 21, "saveAbility": "DEX",
                 "halfOnSave": true, "effects": [{"effectType": "DAMAGE", "damageDice": "18d6", "damageType": "fire"},
                 {"effectType": "CONDITION", "condition": "Frightened"}]}""", "{}", List.of(aria, bram));

        Effect effect = plan.steps().getFirst().effect();
        assertEquals(new Effect.Save(Ability.DEX, 21, true), effect.delivery());
        assertEquals(List.of("frightened"), effect.conditions());
        assertEquals(List.of(aria, bram), plan.steps().getFirst().targetIds());
    }

    @Test
    @DisplayName("The DM's override replaces the template's attack bonus or DC")
    void overrides() throws Exception {
        var attack = monsterEffects.plan(json("""
                {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 4,
                 "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "slashing"}]}"""),
                json("{}"), List.of(aria), 9, null, true);
        var save = monsterEffects.plan(json("""
                {"name": "Gaze", "deliveryMethod": "SAVING_THROW", "saveDC": 12, "saveAbility": "WIS",
                 "effects": [{"effectType": "CONDITION", "condition": "charmed"}]}"""),
                json("{}"), List.of(aria), null, 16, null);

        assertEquals(new Effect.Attack(9, RollMode.ADVANTAGE, true, false), attack.steps().getFirst().effect().delivery());
        assertEquals(16, ((Effect.Save) save.steps().getFirst().effect().delivery()).dc());
    }

    @Test
    @DisplayName("AUTO_DAMAGE applies its damage without its conditions, and AUTO_HIT with them")
    void automaticDeliveries() throws Exception {
        String effects = "[{\"effectType\": \"DAMAGE\", \"damageDice\": \"2d6\", \"damageType\": \"poison\"},"
                + " {\"effectType\": \"CONDITION\", \"condition\": \"poisoned\"}]";

        var autoDamage = plan("{\"name\": \"Aura\", \"deliveryMethod\": \"AUTO_DAMAGE\", \"effects\": " + effects + "}",
                "{}", List.of(aria));
        var autoHit = plan("{\"name\": \"Cloud\", \"effects\": " + effects + "}", "{}", List.of(aria));

        assertTrue(autoDamage.steps().getFirst().effect().conditions().isEmpty());
        assertInstanceOf(Effect.Automatic.class, autoHit.steps().getFirst().effect().delivery());
        assertEquals(List.of("poisoned"), autoHit.steps().getFirst().effect().conditions());
    }

    @Test
    @DisplayName("Actions the template leaves to the DM come back with the reason and no steps")
    void manualActions() throws Exception {
        assertEquals("Change Shape description", plan("""
                {"name": "Change Shape", "automatable": false, "description": "Change Shape description"}""",
                "{}", List.of()).manualReason());
        assertEquals("Delivery method 'NONE' requires DM adjudication", plan("""
                {"name": "Frightful Presence", "deliveryMethod": "NONE",
                 "effects": [{"effectType": "CONDITION", "condition": "frightened"}]}""", "{}", List.of()).manualReason());
        assertEquals("Action contains BUFF effect type requiring DM adjudication", plan("""
                {"name": "Roar", "effects": [{"effectType": "BUFF", "description": "Allies gain advantage"}]}""",
                "{}", List.of()).manualReason());
        assertEquals("Bad's damage dice can't be rolled automatically", plan("""
                {"name": "Bad", "deliveryMethod": "ATTACK_ROLL",
                 "effects": [{"effectType": "DAMAGE", "damageDice": "lots", "damageType": "fire"}]}""",
                "{}", List.of()).manualReason());
    }

    @Test
    @DisplayName("Healing is left to the DM, with a note, and the rest of the action still resolves")
    void healingIsLeftToTheDm() throws Exception {
        var plan = plan("""
                {"name": "Drain", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 5,
                 "effects": [{"effectType": "DAMAGE", "damageDice": "1d8", "damageType": "necrotic"},
                             {"effectType": "HEAL", "healingDice": "1d8"}]}""", "{}", List.of(aria));

        assertTrue(plan.automated());
        assertEquals(List.of("Drain's healing is left to the DM."), plan.notes());
        assertNull(plan.steps().getFirst().effect().healing());
    }

    @Test
    @DisplayName("A multiattack becomes one step per component, found in any of the monster's action lists")
    void multiattack() throws Exception {
        String templates = """
                {"actions": [{"name": "Bite", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 7,
                              "effects": [{"effectType": "DAMAGE", "damageDice": "1d10", "damageType": "piercing"}]},
                             {"name": "Claw", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 7,
                              "effects": [{"effectType": "DAMAGE", "damageDice": "1d6", "damageType": "slashing"}]}],
                 "legendaryActions": [{"name": "Tail", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 7,
                              "effects": [{"effectType": "DAMAGE", "damageDice": "2d8", "damageType": "bludgeoning"}]}]}""";

        var plan = plan("""
                {"name": "Multiattack", "isMultiattack": true,
                 "multiattackComponents": ["Bite", "claw", "Claw", "Tail", "Wing"]}""", templates, List.of(aria));

        assertEquals(List.of("Bite", "Claw", "Claw", "Tail"), plan.steps().stream().map(MonsterEffects.Step::label).toList());
        assertEquals(List.of("Component 'Wing' not found."), plan.notes());
        assertTrue(plan.automated());
    }

    @Test
    @DisplayName("Eye Rays fire rayCount rays chosen by the dice, each a save against the next target in turn")
    void eyeRays() throws Exception {
        dice.script(1, 1);
        var plan = plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rayCount": 2, "rays": [
                  {"name": "Charm Ray", "saveDC": 16, "saveAbility": "WIS",
                   "effects": [{"effectType": "CONDITION", "condition": "charmed"}]},
                  {"name": "Paralyzing Ray", "saveDC": 16, "saveAbility": "CON",
                   "effects": [{"effectType": "CONDITION", "condition": "paralyzed"}]},
                  {"name": "Death Ray", "saveDC": 16, "halfOnSave": true,
                   "effects": [{"effectType": "DAMAGE", "damageDice": "10d10", "damageType": "necrotic"}]}]}""",
                "{}", List.of(aria, bram));

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
    void eyeRaysWithoutTargets() throws Exception {
        assertEquals("No valid targets for eye rays", plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rays": [{"name": "Ray"}]}""", "{}", List.of()).manualReason());
        assertEquals("Eye Rays has no rays defined", plan("""
                {"name": "Eye Rays", "isEyeRays": true, "rays": []}""", "{}", List.of(aria)).manualReason());
    }
}
