package com.tabletophelper.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpellSlotCalculatorTest {

    @Test
    @DisplayName("Level 1 full caster gets 2 first-level slots")
    void fullCasterLevel1() {
        var entries = Map.of("Wizard", new SpellSlotCalculator.ClassEntry(1, "full"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        assertEquals(2, slots.get("1"));
        assertNull(slots.get("2"));
    }

    @Test
    @DisplayName("Level 5 full caster gets correct slots (4/3/2)")
    void fullCasterLevel5() {
        var entries = Map.of("Wizard", new SpellSlotCalculator.ClassEntry(5, "full"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(2, slots.get("3"));
        assertNull(slots.get("4"));
    }

    @Test
    @DisplayName("Level 20 full caster gets all slots including 9th level")
    void fullCasterLevel20() {
        var entries = Map.of("Wizard", new SpellSlotCalculator.ClassEntry(20, "full"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(3, slots.get("3"));
        assertEquals(3, slots.get("4"));
        assertEquals(3, slots.get("5"));
        assertEquals(2, slots.get("6"));
        assertEquals(2, slots.get("7"));
        assertEquals(1, slots.get("8"));
        assertEquals(1, slots.get("9"));
    }

    @Test
    @DisplayName("Half caster uses half level for slot calculation")
    void halfCasterLevel6() {
        var entries = Map.of("Paladin", new SpellSlotCalculator.ClassEntry(6, "half"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // level 6 half caster = 3 caster levels → 4/2 slots
        assertEquals(4, slots.get("1"));
        assertEquals(2, slots.get("2"));
        assertNull(slots.get("3"));
    }

    @Test
    @DisplayName("Third caster uses third level for slot calculation")
    void thirdCasterLevel9() {
        var entries = Map.of("Eldritch Knight", new SpellSlotCalculator.ClassEntry(9, "third"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // level 9 third caster = 3 caster levels → 4/2 slots
        assertEquals(4, slots.get("1"));
        assertEquals(2, slots.get("2"));
    }

    @Test
    @DisplayName("Warlock pact slots are separate from regular slots")
    void warlockPactSlots() {
        var entries = Map.of("Warlock", new SpellSlotCalculator.ClassEntry(5, "pact"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        assertNull(slots.get("1"));
        assertNull(slots.get("2"));
        assertEquals(2, slots.get("pact_3"));
    }

    @Test
    @DisplayName("Multiclass: Wizard 5 / Cleric 3 uses combined caster level")
    void multiclassFullCasters() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Wizard", new SpellSlotCalculator.ClassEntry(5, "full"));
        entries.put("Cleric", new SpellSlotCalculator.ClassEntry(3, "full"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // 5 + 3 = 8 caster levels → level 8 full caster table
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(3, slots.get("3"));
        assertEquals(2, slots.get("4"));
        assertNull(slots.get("5"));
    }

    @Test
    @DisplayName("Multiclass with Warlock: pact slots separate from regular")
    void multiclassWithWarlock() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Wizard", new SpellSlotCalculator.ClassEntry(5, "full"));
        entries.put("Warlock", new SpellSlotCalculator.ClassEntry(3, "pact"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // Wizard 5 = 5 caster levels → 4/3/2
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(2, slots.get("3"));
        // Warlock 3 pact = 2 pact slots at level 2
        assertEquals(2, slots.get("pact_2"));
    }

    @Test
    @DisplayName("Empty entries return empty slots")
    void emptyEntries() {
        var slots = SpellSlotCalculator.calculateSlots(Map.of());
        assertTrue(slots.isEmpty());
    }

    @Test
    @DisplayName("Non-caster returns empty slots")
    void nonCaster() {
        var entries = Map.of("Fighter", new SpellSlotCalculator.ClassEntry(5, "none"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        assertTrue(slots.isEmpty());
    }

    @Test
    @DisplayName("Artificer uses ceil(level/2) for caster level")
    void artificerCasterLevel() {
        var entries = Map.of("Artificer", new SpellSlotCalculator.ClassEntry(5, "artificer"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // level 5 artificer = (5+1)/2 = 3 caster levels → 4/2
        assertEquals(4, slots.get("1"));
        assertEquals(2, slots.get("2"));
    }

    @Test
    @DisplayName("Half caster rounding: level 2 paladin has 1 caster level")
    void halfCasterRounding() {
        var entries = Map.of("Paladin", new SpellSlotCalculator.ClassEntry(2, "half"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // level 2 / 2 = 1 caster level → 2 first-level slots
        assertEquals(2, slots.get("1"));
    }

    @Test
    @DisplayName("Multiclass full + half caster: Wizard 5 + Paladin 4 = caster level 7")
    void multiclassFullPlusHalf() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Wizard", new SpellSlotCalculator.ClassEntry(5, "full"));
        entries.put("Paladin", new SpellSlotCalculator.ClassEntry(4, "half"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // 5 + floor(4/2) = 5 + 2 = 7 caster levels
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(3, slots.get("3"));
        assertEquals(1, slots.get("4"));
        assertNull(slots.get("5"));
    }

    @Test
    @DisplayName("Multiclass full + third caster: Cleric 5 + EK 6 = caster level 7")
    void multiclassFullPlusThird() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Cleric", new SpellSlotCalculator.ClassEntry(5, "full"));
        entries.put("Eldritch Knight", new SpellSlotCalculator.ClassEntry(6, "third"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // 5 + floor(6/3) = 5 + 2 = 7 caster levels
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(3, slots.get("3"));
        assertEquals(1, slots.get("4"));
        assertNull(slots.get("5"));
    }

    @Test
    @DisplayName("Multiclass half + third caster: Paladin 6 + EK 6 = caster level 5")
    void multiclassHalfPlusThird() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Paladin", new SpellSlotCalculator.ClassEntry(6, "half"));
        entries.put("Eldritch Knight", new SpellSlotCalculator.ClassEntry(6, "third"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // floor(6/2) + floor(6/3) = 3 + 2 = 5 caster levels
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertEquals(2, slots.get("3"));
        assertNull(slots.get("4"));
    }

    @Test
    @DisplayName("Multiclass Artificer + Wizard: Artificer 3 + Wizard 2 = caster level 4")
    void multiclassArtificerPlusWizard() {
        var entries = new LinkedHashMap<String, SpellSlotCalculator.ClassEntry>();
        entries.put("Artificer", new SpellSlotCalculator.ClassEntry(3, "artificer"));
        entries.put("Wizard", new SpellSlotCalculator.ClassEntry(2, "full"));
        var slots = SpellSlotCalculator.calculateSlots(entries);
        // ceil(3/2) + 2 = (3+1)/2 + 2 = 2 + 2 = 4 caster levels
        assertEquals(4, slots.get("1"));
        assertEquals(3, slots.get("2"));
        assertNull(slots.get("3"));
    }

    @Test
    @DisplayName("Warlock pact slot level progression from level 1 to 9")
    void warlockPactSlotProgression() {
        // Levels 1-2: pact slots at 1st level
        var slots1 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(1, "pact")));
        assertEquals(1, slots1.get("pact_1"));

        var slots2 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(2, "pact")));
        assertEquals(2, slots2.get("pact_1"));

        // Levels 3-4: pact slots at 2nd level
        var slots3 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(3, "pact")));
        assertEquals(2, slots3.get("pact_2"));
        assertNull(slots3.get("pact_1"));

        var slots4 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(4, "pact")));
        assertEquals(2, slots4.get("pact_2"));

        // Levels 5-6: pact slots at 3rd level
        var slots5 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(5, "pact")));
        assertEquals(2, slots5.get("pact_3"));

        var slots6 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(6, "pact")));
        assertEquals(2, slots6.get("pact_3"));

        // Levels 7-8: pact slots at 4th level
        var slots7 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(7, "pact")));
        assertEquals(2, slots7.get("pact_4"));

        var slots8 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(8, "pact")));
        assertEquals(2, slots8.get("pact_4"));

        // Level 9+: pact slots at 5th level
        var slots9 = SpellSlotCalculator.calculateSlots(
                Map.of("Warlock", new SpellSlotCalculator.ClassEntry(9, "pact")));
        assertEquals(2, slots9.get("pact_5"));
    }
}
