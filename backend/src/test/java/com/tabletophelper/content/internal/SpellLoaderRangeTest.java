package com.tabletophelper.content.internal;

import com.tabletophelper.shared.JacksonDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A spell's range as the spells table words it (M18 Metamagic pass): an area from the caster, which 5etools gives as a
 * range of that shape, reads "Self (15-foot cone)" as in the PHB, not "15 feet", so Distant and Twinned Spell see a range
 * of self (PHB p.102).
 */
class SpellLoaderRangeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"point\",\"distance\":{\"type\":\"feet\",\"amount\":150}}|150 feet",
            "{\"type\":\"point\",\"distance\":{\"type\":\"touch\"}}|Touch",
            "{\"type\":\"point\",\"distance\":{\"type\":\"self\"}}|Self",
            "{\"type\":\"point\",\"distance\":{\"type\":\"sight\"}}|Sight",
            "{\"type\":\"cone\",\"distance\":{\"type\":\"feet\",\"amount\":15}}|Self (15-foot cone)",
            "{\"type\":\"radius\",\"distance\":{\"type\":\"feet\",\"amount\":5}}|Self (5-foot radius)",
            "{\"type\":\"line\",\"distance\":{\"type\":\"feet\",\"amount\":100}}|Self (100-foot line)",
            "{\"type\":\"radius\",\"distance\":{\"type\":\"miles\",\"amount\":5}}|Self (5-mile radius)",
            "{\"type\":\"point\",\"distance\":{\"type\":\"miles\",\"amount\":1}}|1 mile",
            "{\"type\":\"point\",\"distance\":{\"type\":\"miles\",\"amount\":500}}|500 miles",
            "{\"type\":\"point\",\"distance\":{\"type\":\"feet\",\"amount\":1}}|1 foot",
            "{\"type\":\"point\",\"distance\":{\"type\":\"feet\",\"amount\":5}}|5 feet",
            "{\"type\":\"point\",\"distance\":{\"type\":\"unlimited\"}}|Unlimited",
            "{\"type\":\"radius\",\"distance\":{\"type\":\"miles\",\"amount\":1}}|Self (1-mile radius)",
            "{\"type\":\"special\"}|Special"})
    @DisplayName("5etools ranges read as the PHB words them")
    void ranges(String json, String expected) {
        assertEquals(expected, SpellLoader.parseRange(JacksonDefaults.mapper().readTree(json)));
    }
}
