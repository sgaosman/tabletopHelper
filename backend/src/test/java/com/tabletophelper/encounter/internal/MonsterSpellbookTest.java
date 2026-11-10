package com.tabletophelper.encounter.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MonsterSpellbook#canonicalName}, the name a monster's spell is looked up by in the spells table and so for
 * its effect template (D191). The lookup ignores case, so the case is kept as listed.
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
}
