package com.tabletophelper.seeder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FiveEToolsMarkupParserTest {

    @Test
    @DisplayName("{@atk mw} converts to 'Melee Weapon Attack:'")
    void atk_meleeWeapon() {
        String result = FiveEToolsMarkupParser.parse("{@atk mw}");
        assertEquals("Melee Weapon Attack:", result);
    }

    @Test
    @DisplayName("{@atk rw} converts to 'Ranged Weapon Attack:'")
    void atk_rangedWeapon() {
        String result = FiveEToolsMarkupParser.parse("{@atk rw}");
        assertEquals("Ranged Weapon Attack:", result);
    }

    @Test
    @DisplayName("{@hit 5} converts to '+5'")
    void hit_convertsToPlus() {
        String result = FiveEToolsMarkupParser.parse("{@hit 5}");
        assertEquals("+5", result);
    }

    @Test
    @DisplayName("{@damage 2d6+3} converts to '2d6+3'")
    void damage_preservesDiceExpression() {
        String result = FiveEToolsMarkupParser.parse("{@damage 2d6+3}");
        assertEquals("2d6+3", result);
    }

    @Test
    @DisplayName("{@dc 15} converts to 'DC 15'")
    void dc_addsDcPrefix() {
        String result = FiveEToolsMarkupParser.parse("{@dc 15}");
        assertEquals("DC 15", result);
    }

    @Test
    @DisplayName("{@spell fireball} extracts spell name")
    void spell_extractsName() {
        String result = FiveEToolsMarkupParser.parse("{@spell fireball}");
        assertEquals("fireball", result);
    }

    @Test
    @DisplayName("{@creature goblin|mm} extracts creature name stripping source")
    void creature_extractsNameStrippingSource() {
        String result = FiveEToolsMarkupParser.parse("{@creature goblin|mm}");
        assertEquals("goblin", result);
    }

    @Test
    @DisplayName("{@condition poisoned} extracts condition name")
    void condition_extractsName() {
        String result = FiveEToolsMarkupParser.parse("{@condition poisoned}");
        assertEquals("poisoned", result);
    }

    @Test
    @DisplayName("Multiple tags in one string are all processed")
    void multipleTags_allProcessed() {
        String input = "{@atk mw} {@hit 7} to hit, {@damage 2d6+4} slashing damage";
        String result = FiveEToolsMarkupParser.parse(input);
        assertEquals("Melee Weapon Attack: +7 to hit, 2d6+4 slashing damage", result);
    }

    @Test
    @DisplayName("Plain text with no tags passes through unchanged")
    void plainText_passesThrough() {
        String input = "The goblin swings its scimitar wildly.";
        String result = FiveEToolsMarkupParser.parse(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("Null input returns null without exception")
    void nullInput_returnsNull() {
        assertNull(FiveEToolsMarkupParser.parse(null));
    }
}
