package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.Multiclassing.Prerequisites;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Multiclassing prerequisites (PHB p.163): a new class needs its own and every current class's. */
class MulticlassingTest {

    // The Multiclassing Prerequisites table rows these cases use
    private static final Prerequisites BARBARIAN = Prerequisites.allOf(Map.of(Ability.STR, 13));
    private static final Prerequisites DRUID = Prerequisites.allOf(Map.of(Ability.WIS, 13));
    private static final Prerequisites FIGHTER = Prerequisites.anyOf(Map.of(Ability.STR, 13, Ability.DEX, 13));
    private static final Prerequisites PALADIN = Prerequisites.allOf(Map.of(Ability.STR, 13, Ability.CHA, 13));
    private static final Prerequisites ROGUE = Prerequisites.allOf(Map.of(Ability.DEX, 13));
    private static final Prerequisites WIZARD = Prerequisites.allOf(Map.of(Ability.INT, 13));

    @Test
    @DisplayName("A barbarian becoming a druid needs both Strength and Wisdom of 13 (PHB p.163's example)")
    void barbarianIntoDruid() {
        Map<String, Prerequisites> barbarian = Map.of("Barbarian", BARBARIAN);

        assertEquals(List.of(), Multiclassing.unmetPrerequisites(
                scores(13, 10, 10, 10, 13, 10), barbarian, "Druid", DRUID));
        assertEquals(List.of("Druid"), Multiclassing.unmetPrerequisites(
                scores(13, 10, 10, 10, 12, 10), barbarian, "Druid", DRUID));
        assertEquals(List.of("Barbarian"), Multiclassing.unmetPrerequisites(
                scores(12, 10, 10, 10, 13, 10), barbarian, "Druid", DRUID));
    }

    @Test
    @DisplayName("Every current class's prerequisites count, not just the first class's")
    void everyCurrentClassCounts() {
        Map<String, Prerequisites> fighterRogue = new LinkedHashMap<>();
        fighterRogue.put("Fighter", FIGHTER);
        fighterRogue.put("Rogue", ROGUE);

        // STR 13 meets the fighter's, but the rogue's DEX 13 is missing
        assertEquals(List.of("Rogue"), Multiclassing.unmetPrerequisites(
                scores(13, 12, 10, 13, 10, 10), fighterRogue, "Wizard", WIZARD));
        // Nothing met: the new class first, then the current classes in order
        assertEquals(List.of("Wizard", "Fighter", "Rogue"), Multiclassing.unmetPrerequisites(
                scores(10, 10, 10, 10, 10, 10), fighterRogue, "Wizard", WIZARD));
        assertTrue(Multiclassing.canAdd(scores(10, 13, 10, 13, 10, 10), fighterRogue, "Wizard", WIZARD));
    }

    @Test
    @DisplayName("Either of the fighter's scores will do, but a paladin needs both of its own")
    void eitherOrBoth() {
        assertTrue(FIGHTER.metBy(scores(13, 8, 10, 10, 10, 10)));
        assertTrue(FIGHTER.metBy(scores(8, 13, 10, 10, 10, 10)));
        assertFalse(FIGHTER.metBy(scores(12, 12, 10, 10, 10, 10)));

        assertTrue(PALADIN.metBy(scores(13, 10, 10, 10, 10, 13)));
        assertFalse(PALADIN.metBy(scores(13, 10, 10, 10, 10, 12)));
    }

    @Test
    @DisplayName("A character that no longer meets its own class's prerequisites can't add another class")
    void currentClassNotMet() {
        // A paladin with CHA 12 can't become a fighter, however strong
        assertFalse(Multiclassing.canAdd(scores(16, 10, 10, 10, 10, 12), Map.of("Paladin", PALADIN),
                "Fighter", FIGHTER));
    }

    @Test
    @DisplayName("Another level in a class the character already has needs no check")
    void existingClassNeedsNoCheck() {
        // A wizard with INT 12, which a single-class wizard may have, levels as a wizard
        assertEquals(List.of(), Multiclassing.unmetPrerequisites(
                scores(10, 10, 10, 12, 10, 10), Map.of("Wizard", WIZARD), "Wizard", WIZARD));
    }

    @Test
    @DisplayName("A class without prerequisites is met by any scores")
    void noPrerequisites() {
        assertTrue(Prerequisites.NONE.metBy(scores(3, 3, 3, 3, 3, 3)));
        assertTrue(Prerequisites.anyOf(Map.of()).metBy(scores(3, 3, 3, 3, 3, 3)));
    }

    private static Map<Ability, Integer> scores(int str, int dex, int con, int intelligence, int wis, int cha) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        scores.put(Ability.STR, str);
        scores.put(Ability.DEX, dex);
        scores.put(Ability.CON, con);
        scores.put(Ability.INT, intelligence);
        scores.put(Ability.WIS, wis);
        scores.put(Ability.CHA, cha);
        return scores;
    }
}
