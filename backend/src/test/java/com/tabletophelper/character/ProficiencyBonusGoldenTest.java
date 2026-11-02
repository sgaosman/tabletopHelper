package com.tabletophelper.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proficiency bonus by character level (PHB p.15) and by challenge rating (MM p.8), typed out here
 * independently of {@link ProficiencyBonus}.
 */
class ProficiencyBonusGoldenTest {

    @ParameterizedTest(name = "Level {0}: +{1}")
    @CsvSource({
            "1, 2", "2, 2", "3, 2", "4, 2",
            "5, 3", "6, 3", "7, 3", "8, 3",
            "9, 4", "10, 4", "11, 4", "12, 4",
            "13, 5", "14, 5", "15, 5", "16, 5",
            "17, 6", "18, 6", "19, 6", "20, 6",
    })
    @DisplayName("Proficiency bonus by character level (PHB p.15)")
    void byCharacterLevel(int level, int expected) {
        assertEquals(expected, ProficiencyBonus.forLevel(level));
    }

    @ParameterizedTest(name = "CR {0}: +{1}")
    @CsvSource({
            "0, 2", "1/8, 2", "1/4, 2", "1/2, 2",
            "1, 2", "2, 2", "3, 2", "4, 2",
            "5, 3", "6, 3", "7, 3", "8, 3",
            "9, 4", "10, 4", "11, 4", "12, 4",
            "13, 5", "14, 5", "15, 5", "16, 5",
            "17, 6", "18, 6", "19, 6", "20, 6",
            "21, 7", "22, 7", "23, 7", "24, 7",
            "25, 8", "26, 8", "27, 8", "28, 8",
            "29, 9", "30, 9",
    })
    @DisplayName("Proficiency bonus by challenge rating (MM p.8)")
    void byChallengeRating(String cr, int expected) {
        assertEquals(expected, ProficiencyBonus.forChallengeRating(cr));
    }

    // SpellResolverEngine passes a monster's challenge rating, rounded up, as its level
    @ParameterizedTest(name = "Level {0}: +{1}")
    @CsvSource({
            "21, 7", "22, 7", "23, 7", "24, 7",
            "25, 8", "26, 8", "27, 8", "28, 8",
            "29, 9", "30, 9",
    })
    @DisplayName("Levels above 20 continue on the challenge rating table")
    void levelsAboveTwentyFollowTheChallengeRatingTable(int level, int expected) {
        assertEquals(expected, ProficiencyBonus.forLevel(level));
    }

    @Test
    @DisplayName("A missing or unreadable challenge rating gets +2")
    void missingOrUnreadableChallengeRating() {
        assertEquals(2, ProficiencyBonus.forChallengeRating(null));
        assertEquals(2, ProficiencyBonus.forChallengeRating("Unknown"));
    }
}
