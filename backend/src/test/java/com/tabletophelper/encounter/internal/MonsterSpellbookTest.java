package com.tabletophelper.encounter.internal;

import com.tabletophelper.monster.MonsterTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MonsterSpellbook#canonicalName}, the name a monster's spell is looked up by in the spells table and so for
 * its effect template (D191). The lookup ignores case, so the case is kept as listed. And the blocks a spellbook is
 * read from: the main block and any additional ones, merged (D193).
 */
class MonsterSpellbookTest {

    @ParameterizedTest(name = "\"{0}\" is looked up as \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "plane shift (self only)|plane shift",
            "alter self (can become Medium when changing his appearance)|alter self",
            "eldritch blast (range 300 ft., +3 bonus to each damage roll)|eldritch blast",
            "conjure elemental (as 6th-level spell; galeb duhr, gargoyle, earth elemental, or xorn only)"
                    + "|conjure elemental",
            "shocking grasp*|shocking grasp",
            "cone of cold **|cone of cold",
            "fireball†|fireball",
            "counterspell¹|counterspell",
            "misty step²³|misty step",
            "hold person* (as 3rd-level spell)|hold person",
            "acid splash (17th level)*|acid splash",
            "Tasha’s Hideous Laughter|Tasha's Hideous Laughter",
            "Melf‘s Acid Arrow|Melf's Acid Arrow",
            "  mage   armor  |mage armor",
            "Cure Wounds|Cure Wounds"
    })
    @DisplayName("Notes in brackets and footnote markers at the end go, apostrophes straighten and spaces close up")
    void canonicalNames(String listed, String expected) {
        assertEquals(expected, MonsterSpellbook.canonicalName(listed));
    }

    @Test
    @DisplayName("A note in brackets inside the name stays, only the ones at its end go")
    void innerBracketsStay() {
        assertEquals("symbol (pain) ward", MonsterSpellbook.canonicalName("symbol (pain) ward (self only)"));
    }

    @Test
    @DisplayName("A name that is nothing but a note keeps its text, trimmed, and null is empty")
    void onlyANote() {
        assertEquals("(self only)", MonsterSpellbook.canonicalName("  (self only) "));
        assertEquals("*", MonsterSpellbook.canonicalName("*"));
        assertEquals("", MonsterSpellbook.canonicalName(null));
        assertEquals("", MonsterSpellbook.canonicalName("   "));
    }

    @Test
    @DisplayName("An entry is looked up by the canonical name of the one it lists, which it keeps")
    void entryLookupName() {
        MonsterSpellbook.Entry entry = new MonsterSpellbook.Entry("invisibility (self only)",
                MonsterSpellbook.Kind.DAILY, null, 1);
        assertEquals("invisibility", entry.lookupName());
        assertEquals("invisibility (self only)", entry.name());
    }

    @Test
    @DisplayName("An additional block's spells, slots and missing numbers join the main block's, which come first")
    void additionalBlocksMerge() {
        // Nezznar's shape: an innate main block without numbers, and wizard spells with slots in a second block
        MonsterSpellbook book = MonsterSpellbook.read(MonsterTemplates.parse("""
                {"spellcasting": {"innateSpells": false, "atWillSpells": ["dancing lights"],
                  "dailySpells": {"1e": ["darkness"]}},
                 "additionalSpellcasting": [
                  {"ability": "INT", "saveDC": 13, "attackBonus": 5, "innate": false, "slots": {"2": 3, "1": 4},
                   "spellsByLevel": {"0": ["ray of frost"], "1": ["magic missile"]}},
                  {"ability": "WIS", "saveDC": 99, "daily": {"3e": ["stone shape"]}, "slots": {"1": 9, "3": 1}}]}"""));

        assertEquals("INT", book.ability());
        assertEquals(5, book.attackBonus());
        assertEquals(13, book.saveDc());
        assertEquals(Map.of("1", 4, "2", 3, "3", 1), book.slots());
        assertEquals(List.of("1", "2", "3"), List.copyOf(book.slots().keySet()));
        assertEquals(List.of(
                new MonsterSpellbook.Entry("darkness", MonsterSpellbook.Kind.DAILY, null, 1),
                new MonsterSpellbook.Entry("dancing lights", MonsterSpellbook.Kind.AT_WILL, null, 0),
                new MonsterSpellbook.Entry("ray of frost", MonsterSpellbook.Kind.LEVELLED, 0, 0),
                new MonsterSpellbook.Entry("magic missile", MonsterSpellbook.Kind.LEVELLED, 1, 0),
                new MonsterSpellbook.Entry("stone shape", MonsterSpellbook.Kind.DAILY, null, 3)), book.spells());
    }

    @Test
    @DisplayName("The main block's numbers win over an additional block's, and additional blocks alone are read")
    void mainBlockNumbersWin() {
        MonsterSpellbook main = MonsterSpellbook.read(MonsterTemplates.parse("""
                {"spellcasting": {"ability": "CHA", "saveDC": 15, "attackBonus": 7, "atWillSpells": ["darkness"]},
                 "additionalSpellcasting": [{"ability": "INT", "saveDC": 17, "attackBonus": 9,
                  "atWillSpells": ["darkness"], "dailySpells": {"1e": ["fireball"]}}]}"""));
        MonsterSpellbook onlyAdditional = MonsterSpellbook.read(MonsterTemplates.parse("""
                {"additionalSpellcasting": [{"ability": "WIS", "saveDC": 12, "dailySpells": {"1e": ["bless"]}}]}"""));

        assertEquals("CHA", main.ability());
        assertEquals(15, main.saveDc());
        assertEquals(7, main.attackBonus());
        assertEquals(2, main.spells().size());
        assertTrue(onlyAdditional.present());
        assertEquals(12, onlyAdditional.saveDc());
        assertEquals(List.of(new MonsterSpellbook.Entry("bless", MonsterSpellbook.Kind.DAILY, null, 1)),
                onlyAdditional.spells());
        assertFalse(MonsterSpellbook.read(MonsterTemplates.parse("{\"actions\": []}")).present());
    }
}
