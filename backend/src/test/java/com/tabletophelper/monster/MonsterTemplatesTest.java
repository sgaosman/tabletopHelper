package com.tabletophelper.monster;

import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading monster action templates into records (D168). The JSON is the seeded data as the local database holds it,
 * cut to the entries each case needs: the Adult Red Dragon (MM), the Illithilich, the Pixie, the Octopus, the
 * Beholder, the Dire Wolf, the Rug of Smothering and the Mind Flayer. Across all 2,357 monsters, every key the records
 * read has one JSON type, except two: a legendary action's cost, which is a number or, twice, the text "1-3", and
 * spellcasting's innateSpells, a flag on 750 monsters and an object on 18.
 */
public class MonsterTemplatesTest {


    // The Adult Red Dragon's templates, every list and count it has, with its keys as seeded
    public static final String ADULT_RED_DRAGON = """
            {"source": "MM", "monsterName": "Adult Red Dragon", "challengeRating": "17", "hasLairActions": true,
             "lairGroupName": "Red Dragon", "lairActionInitiative": 20,
             "legendaryActionCount": 3, "legendaryResistanceCount": 3,
             "traits": [{"name": "Legendary Resistance (3/Day)", "uses": 3, "isLegendaryResistance": true,
                         "description": "If the dragon fails a saving throw, it can choose to succeed instead."}],
             "actions": [
               {"name": "Multiattack", "actionType": "ACTION", "isMultiattack": true,
                "multiattackComponents": ["Frightful Presence", "Bite", "Claw", "Claw"],
                "multiattackDescription": "The dragon can use its Frightful Presence. It then makes three attacks: one with its bite and two with its claws."},
               {"name": "Bite", "reach": 10, "effects": [{"damageDice": "2d10+8", "damageType": "piercing", "effectType": "DAMAGE"},
                  {"damageDice": "2d6", "damageType": "fire", "effectType": "DAMAGE"}], "actionType": "ACTION",
                "attackType": "MELEE_WEAPON", "attackBonus": 14, "targetCount": 1, "deliveryMethod": "ATTACK_ROLL"},
               {"name": "Claw", "reach": 5, "effects": [{"damageDice": "2d6+8", "damageType": "slashing", "effectType": "DAMAGE"}],
                "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 14, "targetCount": 1,
                "deliveryMethod": "ATTACK_ROLL"},
               {"name": "Tail", "reach": 15, "effects": [{"damageDice": "2d8+8", "damageType": "bludgeoning", "effectType": "DAMAGE"}],
                "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 14, "targetCount": 1,
                "deliveryMethod": "ATTACK_ROLL"},
               {"name": "Frightful Presence", "saveDC": 19, "effects": [{"condition": "frightened", "effectType": "CONDITION",
                  "saveToEndDC": 19, "durationText": "1 minute", "durationRounds": 10, "saveToEndAbility": "WIS",
                  "saveToEndEachTurn": true}], "actionType": "ACTION", "halfOnSave": false, "saveAbility": "WIS",
                "deliveryMethod": "SAVING_THROW"},
               {"name": "Fire Breath", "saveDC": 21, "aoeSize": 60, "effects": [{"damageDice": "18d6", "damageType": "fire",
                  "effectType": "DAMAGE"}], "recharge": "5-6", "actionType": "ACTION", "halfOnSave": true,
                "targetType": "AOE_CONE", "saveAbility": "DEX", "deliveryMethod": "SAVING_THROW"}],
             "legendaryActions": [
               {"cost": 1, "name": "Detect", "automatable": false, "description": "The dragon makes a Wisdom (Perception) check."},
               {"cost": 1, "name": "Tail Attack", "reach": 15, "effects": [{"damageDice": "2d8+8", "damageType": "bludgeoning",
                  "effectType": "DAMAGE"}], "attackType": "MELEE_WEAPON", "attackBonus": 14, "targetCount": 1,
                "deliveryMethod": "ATTACK_ROLL", "referencesAction": "Tail"},
               {"cost": 2, "name": "Wing Attack", "saveDC": 22, "effects": [{"damageDice": "2d6+8", "damageType": "bludgeoning",
                  "effectType": "DAMAGE"}, {"condition": "prone", "effectType": "CONDITION", "durationText": "knocked prone",
                  "durationRounds": null}], "halfOnSave": false, "saveAbility": "DEX", "deliveryMethod": "SAVING_THROW"}],
             "lairActions": [
               {"name": "Tremor", "saveDC": 15, "aoeSize": 60, "effects": [{"condition": "prone", "effectType": "CONDITION"}],
                "actionType": "LAIR", "halfOnSave": false, "targetType": "AOE_RADIUS", "automatable": true,
                "description": "A tremor shakes the lair in a 60-foot radius around the dragon.", "saveAbility": "DEX",
                "deliveryMethod": "SAVING_THROW"}],
             "reactions": [{"name": "Wing Attack (Costs 2 Actions)", "actionType": "REACTION", "automatable": false}]}""";

    private MonsterTemplates parse(String json) {
        return MonsterTemplates.parse(json, "The templates");
    }

    @Test
    @DisplayName("The Adult Red Dragon reads its lists, counts and actions, every damage part with its type")
    void adultRedDragon() {
        MonsterTemplates dragon = parse(ADULT_RED_DRAGON);

        assertEquals(List.of("Multiattack", "Bite", "Claw", "Tail", "Frightful Presence", "Fire Breath"),
                dragon.actions().stream().map(MonsterTemplates.Action::name).toList());
        assertEquals(3, dragon.legendaryActionCount());
        assertEquals(3, dragon.legendaryResistanceCount());
        assertTrue(dragon.hasLairActions());
        assertNull(dragon.spellcasting());

        MonsterTemplates.Action multiattack = dragon.actions().getFirst();
        assertTrue(multiattack.multiattack());
        assertEquals(List.of("Frightful Presence", "Bite", "Claw", "Claw"), multiattack.multiattackComponents());
        assertNull(multiattack.multiattackNote());

        MonsterTemplates.Action bite = dragon.actions().get(1);
        assertEquals("ATTACK_ROLL", bite.deliveryMethod());
        assertEquals(14, bite.attackBonus());
        assertEquals(1, bite.targetCount());
        assertEquals(List.of(
                new MonsterTemplates.Part("DAMAGE", "2d10+8", "piercing", null, null, null, null, false, null, null, null),
                new MonsterTemplates.Part("DAMAGE", "2d6", "fire", null, null, null, null, false, null, null, null)),
                bite.effects());

        MonsterTemplates.Part frightened = dragon.actions().get(4).effects().getFirst();
        assertEquals(new MonsterTemplates.Part("CONDITION", null, null, "frightened", null, null, 10, true, 19, "WIS", null),
                frightened);
        assertEquals("5-6", dragon.actions().get(5).recharge());
    }

    @Test
    @DisplayName("An attack reads the save its data keeps apart from saveDC, as the Mind Flayer's tentacles do")
    void secondarySave() {
        MonsterTemplates.Action tentacle = parse("""
                {"actions": [{"name": "Tentacle", "deliveryMethod": "ATTACK_ROLL", "attackBonus": 7,
                  "secondarySaveDC": 15, "secondarySaveAbility": "INT",
                  "effects": [{"condition": "stunned", "effectType": "CONDITION"}]}]}""").actions().getFirst();

        assertEquals(15, tentacle.secondarySaveDc());
        assertEquals("INT", tentacle.secondarySaveAbility());
        assertNull(tentacle.saveDc());
    }

    @Test
    @DisplayName("A condition an attack gives on a hit reads the save the data records on it, as the Dire Wolf's prone "
            + "does, or no ability where the data has none, as the Rug of Smothering's blinded")
    void conditionSaves() {
        MonsterTemplates wolf = parse("""
                {"actions": [{"name": "Bite", "reach": 5, "effects": [
                   {"damageDice": "2d6 + 3", "damageType": "piercing", "effectType": "DAMAGE"},
                   {"saveDC": 13, "condition": "prone", "effectType": "CONDITION", "saveAbility": "STR"}],
                  "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 5, "targetCount": 1,
                  "deliveryMethod": "ATTACK_ROLL"}]}""");
        MonsterTemplates rug = parse("""
                {"actions": [{"name": "Smother", "effects": [
                   {"saveDC": 13, "condition": "blinded", "effectType": "CONDITION", "saveAbility": null}],
                  "deliveryMethod": "ATTACK_ROLL"}]}""");

        assertEquals(new MonsterTemplates.Part("CONDITION", null, null, "prone", 13, "STR", null, false, null, null, null),
                wolf.actions().getFirst().effects().get(1));
        assertEquals(new MonsterTemplates.Part("CONDITION", null, null, "blinded", 13, null, null, false, null, null, null),
                rug.actions().getFirst().effects().getFirst());
    }

    @Test
    @DisplayName("Actions are found by source and exact name; a multiattack's components in any case and in any list")
    void lookups() {
        MonsterTemplates dragon = parse(ADULT_RED_DRAGON);

        assertEquals("Wing Attack", dragon.find("LEGENDARY", "Wing Attack").orElseThrow().name());
        assertEquals("Tremor", dragon.find("LAIR", "Tremor").orElseThrow().name());
        assertTrue(dragon.find("ACTION", "Wing Attack").isEmpty());
        assertTrue(dragon.find("ACTION", "bite").isEmpty());

        assertEquals("Bite", dragon.component("bite").orElseThrow().name());
        assertEquals("Wing Attack", dragon.component("wing attack").orElseThrow().name());
        assertEquals("Wing Attack (Costs 2 Actions)", dragon.component("Wing Attack (Costs 2 Actions)").orElseThrow()
                .name());
        assertTrue(dragon.component("Wing").isEmpty());

        // The actions come first when two lists share a name
        MonsterTemplates shared = parse("""
                {"legendaryActions": [{"name": "Claw", "cost": 1, "attackBonus": 9}],
                 "actions": [{"name": "Claw", "attackBonus": 7}]}""");
        assertEquals(7, shared.component("claw").orElseThrow().attackBonus());
    }

    @Test
    @DisplayName("A legendary action's cost is a number, or text such as the Illithilich's 1-3, which costs 1")
    void legendaryCosts() {
        MonsterTemplates illithilich = parse("""
                {"legendaryActions": [
                   {"cost": 1, "name": "Tentacles", "automatable": false,
                    "description": "The illithilich makes one attack with its tentacles."},
                   {"cost": 3, "name": "Mind Blast", "automatable": false,
                    "description": "The illithilich recharges its Mind Blast and uses it."},
                   {"cost": "1-3", "name": "Cast Spell", "automatable": false, "variableCost": true,
                    "description": "The illithilich uses a spell slot to cast a 1st-, 2nd-, or 3rd-level spell that it has prepared."},
                   {"name": "Uncosted"}]}""");

        assertEquals(List.of(1, 3, 1, 1), illithilich.legendaryActions().stream()
                .map(MonsterTemplates.Action::legendaryCost).toList());
        assertEquals("1-3", illithilich.legendaryActions().get(2).cost());
        assertEquals(Boolean.FALSE, illithilich.legendaryActions().getFirst().automatable());
    }

    @Test
    @DisplayName("The spellcasting block keeps its lists in order, and a Pixie's innate lists sit in innateSpells")
    void spellcasting() {
        MonsterTemplates illithilich = parse("""
                {"spellcasting": {"name": "Spellcasting", "slots": {"1": 4, "2": 3, "9": 1}, "saveDC": 20, "ability": "INT",
                  "attackBonus": 12, "dailySpells": {"1e": ["dominate monster", "plane shift (self only)"]},
                  "atWillSpells": ["detect thoughts", "levitate"], "innateSpells": false,
                  "spellsByLevel": {"0": ["mage hand", "prestidigitation"], "1": ["detect magic", "magic missile"]}}}""");
        MonsterTemplates pixie = parse("""
                {"spellcasting": {"name": "Innate Spellcasting", "saveDC": 12, "ability": "CHA", "attackBonus": null,
                  "innateSpells": {"atWill": ["druidcraft"], "1/day each": ["confusion", "dancing lights"]}}}""");

        MonsterTemplates.Spellcasting book = illithilich.spellcasting();
        assertEquals("INT", book.ability());
        assertEquals(12, book.attackBonus());
        assertEquals(20, book.saveDc());
        assertEquals(List.of("1", "2", "9"), List.copyOf(book.slots().keySet()));
        assertEquals(List.of("0", "1"), List.copyOf(book.spellsByLevel().keySet()));
        assertEquals(List.of("dominate monster", "plane shift (self only)"), book.dailySpells().get("1e"));
        assertEquals(List.of("detect thoughts", "levitate"), book.atWillSpells());
        assertTrue(book.innateLists().isEmpty());

        assertNull(pixie.spellcasting().attackBonus());
        assertEquals(Map.of("atWill", List.of("druidcraft"), "1/day each", List.of("confusion", "dancing lights")),
                pixie.spellcasting().innateLists());
        assertTrue(pixie.spellcasting().slots().isEmpty());
    }

    @Test
    @DisplayName("Keys the records don't read are ignored, whatever their type, as are a flat amount's flag and an Eye Rays' rays")
    void otherKeysAndShapes() {
        MonsterTemplates octopus = parse("""
                {"notes": ["REVIEW: grapple"], "actions": [
                  {"name": "Tentacle", "reach": 5, "range": {"long": 60, "normal": 20}, "notes": null,
                   "effects": [{"damageDice": "1", "damageType": "bludgeoning", "effectType": "DAMAGE", "flatDamage": true},
                               {"escapeDC": 10, "condition": "grappled", "effectType": "CONDITION"}],
                   "actionType": "ACTION", "attackType": "MELEE_WEAPON", "attackBonus": 4, "targetCount": 1,
                   "deliveryMethod": "ATTACK_ROLL"},
                  {"name": "Spit", "range": "30 ft.", "reach": null},
                  {"name": "Ink", "range": 5},
                  {"name": "Eye Rays", "rayCount": 3, "isEyeRays": true, "actionType": "ACTION", "rays": [
                    {"name": "1. Charm Ray", "saveDC": 16, "effects": [{"condition": "charmed", "effectType": "CONDITION",
                      "durationText": "1 hour", "durationRounds": 600}], "halfOnSave": false, "saveAbility": "WIS",
                     "description": "The targeted creature must succeed on a 16 Wisdom saving throw or be charmed."},
                    {"name": "4. Slowing Ray", "saveDC": 16, "halfOnSave": false, "saveAbility": "DEX"}]}]}""");

        MonsterTemplates.Action tentacle = octopus.actions().getFirst();
        assertEquals("1", tentacle.effects().getFirst().damageDice());
        assertEquals("grappled", tentacle.effects().get(1).condition());
        MonsterTemplates.Action eyeRays = octopus.actions().get(3);
        assertTrue(eyeRays.eyeRays());
        assertEquals(3, eyeRays.rayCount());
        assertEquals(new MonsterTemplates.Ray("4. Slowing Ray", 16, "DEX", false, List.of()), eyeRays.rays().get(1));
        assertEquals(600, eyeRays.rays().getFirst().effects().getFirst().durationRounds());
    }

    @Test
    @DisplayName("No templates are NONE, an array's first entry counts, and data that doesn't fit is a 409 naming it")
    void shapesAndFailures() {
        assertSame(MonsterTemplates.NONE, parse(null));
        assertSame(MonsterTemplates.NONE, parse(" "));
        assertSame(MonsterTemplates.NONE, parse("[]"));
        assertSame(MonsterTemplates.NONE, parse("\"text\""));
        assertTrue(MonsterTemplates.NONE.actions().isEmpty());
        assertFalse(MonsterTemplates.NONE.hasLairActions());
        assertEquals("Bite", parse("[{\"actions\": [{\"name\": \"Bite\"}]}, {}]").actions().getFirst().name());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> parse("{\"actions\": [{\"name\": \"Bite\", \"attackBonus\": {\"melee\": 4}}]}"));
        assertEquals("The templates can't be read", ex.getMessage());
    }
}
