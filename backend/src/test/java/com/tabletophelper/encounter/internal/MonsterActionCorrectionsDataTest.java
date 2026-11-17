package com.tabletophelper.encounter.internal;

import com.tabletophelper.shared.JacksonDefaults;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hand-built entries' numbers D227 puts right against their stat blocks: attack damage the entries misread, the
 * metallic and moonstone dragons' breath weapons split in two with a shared recharge, a success halving a save
 * action's damage, save actions aimed at one creature, multiattack components, the Mummy Lord's Dreadful Glare, the
 * save actions' dice, legendary spellcasting by level and action names without a stray recharge digit.
 */
class MonsterActionCorrectionsDataTest {

    private static final String DEFINITIONS = "data/monster-actions/monster-action-definitions.json";
    private static final List<String> LISTS = List.of("actions", "bonusActions", "reactions", "legendaryActions",
            "mythicActions");
    private static final List<JsonNode> definitions = new ArrayList<>();

    @BeforeAll
    static void readTheData() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            JacksonDefaults.mapper().readTree(in).forEach(definitions::add);
        }
    }

    @AfterAll
    static void letTheDataGo() {
        definitions.clear();
    }

    private static JsonNode definition(String monster, String source) {
        return definitions.stream()
                .filter(d -> monster.equals(d.path("monsterName").asString())
                        && source.equals(d.path("source").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No definition for " + monster + " (" + source + ")"));
    }

    private static JsonNode action(String monster, String source, String name) {
        for (String list : LISTS) {
            for (JsonNode action : definition(monster, source).path(list)) {
                if (name.equals(action.path("name").asString())) {
                    return action;
                }
            }
        }
        throw new AssertionError("No " + name + " for " + monster);
    }

    private static boolean has(String monster, String source, String name) {
        for (String list : LISTS) {
            for (JsonNode action : definition(monster, source).path(list)) {
                if (name.equals(action.path("name").asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    // The action's DAMAGE parts as "dice type", in order
    private static List<String> damage(JsonNode action) {
        List<String> parts = new ArrayList<>();
        for (JsonNode part : action.path("effects")) {
            if ("DAMAGE".equals(part.path("effectType").asString())) {
                parts.add(part.path("damageDice").asString() + " " + part.path("damageType").asString());
            }
        }
        return parts;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    @ParameterizedTest(name = "{0} ({1}) {2}: {3}")
    @CsvSource(delimiter = '|', value = {
            // MM p.346: the two-handed and shillelagh alternatives are in the note, not three stacked parts
            "Druid|MM|Quarterstaff|1d6 bludgeoning",
            "Mummy Lord|MM|Rotting Fist|3d6 + 4 bludgeoning,6d6 necrotic",
            // 9 (1d10 + 4) only in Small or Medium form, an alternative under onlyIf.attackerForm the DM picks (D241)
            "Oni|MM|Glaive|2d10 + 4 slashing,1d10 + 4 slashing",
            // The ignition's damage each turn is the DM's, in the note
            "Fire Elemental|MM|Touch|2d6 + 3 fire",
            "Magmin|MM|Touch|2d6 fire",
            // Gruumsh's Fury, "11 (1d6 + 3 plus 1d8) piercing damage"
            "Orc Eye of Gruumsh|MM|Spear|1d6 + 3 piercing,1d8 piercing",
            "Orc War Chief|MM|Spear|1d6 + 4 piercing,1d8 piercing",
            "Orc War Chief|MM|Greataxe|1d12 + 4 slashing,1d8 slashing",
            // In melee; the thrown javelin's 1d6 is in the note
            "Bugbear|MM|Javelin|2d6 + 2 piercing",
            "Bugbear Chief|MM|Javelin|2d6 + 3 piercing",
            // "X piercing damage plus Y type damage" stored as one part of the second type
            "Archon of Redemption|PSZ|Sword|1d8 + 4 slashing,3d8 radiant",
            "Iymrith|SKT|Bite|2d10 + 9 piercing,2d10 lightning",
            "Klauth|SKT|Bite|2d10 + 10 piercing,4d6 fire",
            // The hit's own damage missing beside its extra damage
            "Asteroid Spider|MCV1SC|Bite|3d8 + 8 piercing,3d6 acid",
            // An attack with no effects at all gets its 1 piercing damage
            "Space Hamster|BAM|Bite|1 piercing",
            // Save actions' failed-save dice
            "Elder Brain Dragon|FTD|Tadpole Brine Breath|10d10 psychic",
            "Dragonborn of Sardior|FTD|Heat Breath|6d8 fire",
            "Eater of Hope|MOT|Breath of Hopelessness|4d12 necrotic",
            "Leviathan|MTF|Tidal Wave|6d10 bludgeoning",
            "Gallows Speaker|VRGR|Suffering Echoes|3d12 psychic",
            "Ender Dragon|MCV3MC|Harmful Breath|15d6 necrotic",
            "Yuan-ti Nightmare Speaker|MPMM|Invoke Nightmare (Recharges after a Short or Long Rest)|4d10 psychic"})
    @DisplayName("Damage as the stat block gives it")
    void damageAsTheStatBlock(String monster, String source, String name, String parts) {
        assertEquals(List.of(parts.split(",")), damage(action(monster, source, name)));
    }

    @Test
    @DisplayName("The adult brass dragon's Breath Weapons are two actions sharing its Recharge 5-6: a 60-foot line "
            + "of fire (DC 18 Dexterity, half on a success) and a 60-foot cone of sleep (DC 18 Constitution, "
            + "unconscious for 10 minutes or until it takes damage) (MM p.105)")
    void brassBreathWeapons() {
        assertFalse(has("Adult Brass Dragon", "MM", "Breath Weapons"));
        JsonNode fire = action("Adult Brass Dragon", "MM", "Fire Breath");
        JsonNode sleep = action("Adult Brass Dragon", "MM", "Sleep Breath");
        for (JsonNode breath : List.of(fire, sleep)) {
            assertEquals("5-6", breath.path("recharge").asString());
            assertEquals("Breath Weapons", breath.path("rechargeGroup").asString());
            assertEquals(18, breath.path("saveDC").asInt());
        }
        assertEquals("AOE_LINE", fire.path("targetType").asString());
        assertEquals(60, fire.path("aoeSize").asInt());
        assertEquals("DEX", fire.path("saveAbility").asString());
        assertTrue(fire.path("halfOnSave").asBoolean());
        assertEquals(List.of("13d6 fire"), damage(fire));
        assertEquals("AOE_CONE", sleep.path("targetType").asString());
        assertEquals("CON", sleep.path("saveAbility").asString());
        assertTrue(damage(sleep).isEmpty());
        JsonNode unconscious = sleep.path("effects").get(0);
        assertEquals("unconscious", unconscious.path("condition").asString());
        assertEquals(100, unconscious.path("durationRounds").asInt());
        assertTrue(unconscious.path("endsOnDamage").asBoolean());
    }

    @Test
    @DisplayName("The gold dragon's Weakening Breath is there beside its Fire Breath, and the metallic, moonstone and "
            + "metallic greatwyrm breaths are 62 actions on 31 monsters sharing a recharge")
    void breathWeaponCounts() {
        JsonNode weakening = action("Adult Gold Dragon", "MM", "Weakening Breath");
        assertEquals("Breath Weapons", weakening.path("rechargeGroup").asString());
        assertFalse(weakening.path("automatable").asBoolean(true));
        assertEquals(List.of("12d10 fire"), damage(action("Adult Gold Dragon", "MM", "Fire Breath")));
        assertEquals("AOE_LINE", action("Young Moonstone Dragon", "FTD", "Moonlight Breath").path("targetType")
                .asString());
        int actions = 0;
        int monsters = 0;
        for (JsonNode definition : definitions) {
            int grouped = 0;
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    grouped += action.hasNonNull("rechargeGroup") ? 1 : 0;
                }
            }
            actions += grouped;
            monsters += grouped > 0 ? 1 : 0;
        }
        assertEquals(62, actions);
        assertEquals(31, monsters);
    }

    @Test
    @DisplayName("The mummy lord's Dreadful Glare, and its legendary use, frighten on a failed save; the paralysis on a "
            + "save failed by 5 or more is the DM's, as the mummy's is (MM p.228, p.229)")
    void dreadfulGlare() {
        for (JsonNode glare : List.of(action("Mummy Lord", "MM", "Dreadful Glare"),
                action("Mummy Lord", "MM", "Attack"), action("Mummy", "MM", "Dreadful Glare"))) {
            List<String> conditions = new ArrayList<>();
            glare.path("effects").forEach(part -> conditions.add(part.path("condition").asString()));
            assertEquals(List.of("frightened"), conditions);
            assertTrue(glare.path("dmNote").asString().contains("fails the saving throw by 5 or more"));
        }
        JsonNode mummy = action("Mummy", "MM", "Dreadful Glare");
        assertEquals(1, mummy.path("targetCount").asInt());
        assertFalse(mummy.has("targetType"), "one creature, not a sphere");
    }

    @ParameterizedTest(name = "{0} ({1}) {2}")
    @CsvSource(delimiter = '|', value = {
            "Marid|MM|Water Jet",
            "Adult Amethyst Dragon|FTD|Singularity Breath",
            "Red Greatwyrm|FTD|Breath Weapon",
            "Allip|MTF|Howling Babble",
            "Ogre Chain Brute|MTF|Chain Sweep",
            "Venom Troll|MTF|Venom Spray",
            "Styx Dragon|CoA|Stygian Breath"})
    @DisplayName("A success halves the damage, in a sentence of its own or after the failure's")
    void halfOnASuccess(String monster, String source, String name) {
        assertTrue(action(monster, source, name).path("halfOnSave").asBoolean());
    }

    @Test
    @DisplayName("The witchstalker's howl is a Wisdom save, and the Demogorgon's (IMR) Gaze leaves its effect to "
            + "Beguiling and Hypnotic Gaze, each a DC 14 Wisdom save")
    void savesAsWritten() {
        assertEquals("WIS", action("Witchstalker", "MCV4EC", "Mind-Wracking Howl").path("saveAbility").asString());
        assertFalse(action("\"The Demogorgon\"", "IMR", "Gaze").path("automatable").asBoolean(true));
        assertEquals("stunned", action("\"The Demogorgon\"", "IMR", "Beguiling Gaze").path("effects").get(0)
                .path("condition").asString());
        assertEquals("charmed", action("\"The Demogorgon\"", "IMR", "Hypnotic Gaze").path("effects").get(0)
                .path("condition").asString());
    }

    @Test
    @DisplayName("Legendary spellcasting costing 1 action per spell level is one entry per level, costing 1, 2 and 3")
    void spellsByLevel() {
        for (String[] caster : new String[][] {{"Drow Matron Mother", "MTF", "Cast a Spell"},
                {"Illithilich", "VGM", "Cast Spell"}}) {
            assertFalse(has(caster[0], caster[1], caster[2]));
            String[] levels = {"1st", "2nd", "3rd"};
            for (int level = 1; level <= 3; level++) {
                JsonNode spell = action(caster[0], caster[1], caster[2] + " (" + levels[level - 1] + " Level)");
                assertEquals(level, spell.path("cost").asInt());
            }
        }
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource(delimiter = '|', value = {
            "Iymrith|SKT|Frightful Presence,Bite,Claw,Claw",
            "Tiamat|RoT|Frightful Presence,Claw,Claw,Tail",
            "Archon of Redemption|PSZ|Sword,Claw",
            "Statue of Talos|SLW|Headbutt,Lightning Bolt Blades,Lightning Bolt Blades,Lightning Bolt Blades,"
                    + "Lightning Bolt Blades",
            "Ice Troll|IDRotF|Bite,Claw,Claw",
            "Baphomet|MTF|Heartcleaver,Bite,Gore",
            "Archer|VGM|Longbow,Longbow",
            "Swashbuckler|VGM|Dagger,Rapier,Rapier",
            "Pterafolk|ToA|Bite,Claw,Claw,Javelin,Javelin"})
    @DisplayName("A multiattack makes what its text says")
    void multiattacks(String monster, String source, String components) {
        JsonNode multiattack = null;
        for (JsonNode action : definition(monster, source).path("actions")) {
            if (action.path("isMultiattack").asBoolean()) {
                multiattack = action;
                break;
            }
        }
        assertNotNull(multiattack);
        assertEquals(List.of(components.split(",")), strings(multiattack.path("multiattackComponents")));
    }

    @Test
    @DisplayName("The generator's corrections, each marked so a re-run restores them first: 106 save actions halving "
            + "on a success, 119 multiattacks, 43 save actions on one creature and 8 attacks given their effects")
    void markedCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    JsonNode replaced = action.path("_riderActionReplaced");
                    if (replaced.has("halfOnSave") && !replaced.path("halfOnSave").asBoolean()
                            && action.path("halfOnSave").asBoolean()) {
                        counts.merge("halfOnSave", 1, Integer::sum);
                    }
                    if (replaced.has("multiattackComponents")) {
                        counts.merge("multiattack", 1, Integer::sum);
                    }
                    if (replaced.has("targetType")) {
                        counts.merge("oneTarget", 1, Integer::sum);
                    }
                    if (strings(action.path("_riderActionKeys")).contains("effects")) {
                        counts.merge("effects", 1, Integer::sum);
                    }
                }
            }
        }
        assertEquals(Map.of("halfOnSave", 106, "multiattack", 119, "oneTarget", 43, "effects", 8), counts);
    }

    @Test
    @DisplayName("No action's name ends with its recharge, as \"Fire Breath 5\" did")
    void noRechargeDigitsInNames() {
        Pattern digit = Pattern.compile("\\s[4-6](?:[-\\u2013]6)?$");
        List<String> found = new ArrayList<>();
        for (JsonNode definition : definitions) {
            for (String list : LISTS) {
                for (JsonNode action : definition.path(list)) {
                    if (digit.matcher(action.path("name").asString()).find()) {
                        found.add(definition.path("monsterName").asString() + ": " + action.path("name").asString());
                    }
                }
            }
        }
        assertEquals(List.of(), found);
    }
}
