package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.CurrentHitPoints.Totals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A character sheet's hit points between encounters (PHB p.198), typed out here independently of
 * {@link CurrentHitPoints}: temporary hit points take damage first, hit points stop at 0 and at the maximum, and
 * temporary hit points don't stack (D158, D161); and what a new maximum does to the hit points a character has (D167).
 */
class CurrentHitPointsTest {

    @ParameterizedTest(name = "{0}/{1} with {2} temporary, taking {3}: {4} with {5} temporary")
    @CsvSource({
            // Temporary hit points absorb all of it
            "20, 30, 5, 3, 20, 2",
            "20, 30, 5, 5, 20, 0",
            // The rest comes off the hit points
            "20, 30, 5, 8, 17, 0",
            "20, 30, 0, 8, 12, 0",
            // Hit points stop at 0, however much is left over
            "20, 30, 5, 99, 0, 0",
            "0, 30, 0, 4, 0, 0",
            "20, 30, 0, 0, 20, 0",
    })
    @DisplayName("Damage comes off temporary hit points first, and the rest off hit points, stopping at 0")
    void damage(int current, int maximum, int temporary, int damage, int currentAfter, int temporaryAfter) {
        assertEquals(new Totals(currentAfter, maximum, temporaryAfter),
                CurrentHitPoints.damage(new Totals(current, maximum, temporary), damage));
    }

    @ParameterizedTest(name = "{0}/{1} with {2} temporary, healed {3}: {4}")
    @CsvSource({
            "10, 30, 0, 7, 17",
            "25, 30, 0, 7, 30",
            "30, 30, 0, 7, 30",
            "0, 30, 0, 1, 1",
            // Healing doesn't touch temporary hit points
            "10, 30, 6, 4, 14",
    })
    @DisplayName("Healing stops at the maximum and leaves temporary hit points alone")
    void healing(int current, int maximum, int temporary, int healing, int currentAfter) {
        assertEquals(new Totals(currentAfter, maximum, temporary),
                CurrentHitPoints.heal(new Totals(current, maximum, temporary), healing));
    }

    @ParameterizedTest(name = "{0} temporary, gaining {1}: {2}")
    @CsvSource({"0, 5, 5", "5, 3, 5", "5, 8, 8", "4, 4, 4", "0, 0, 0"})
    @DisplayName("New temporary hit points replace the old only when there are more of them")
    void temporaryHitPoints(int temporary, int gained, int temporaryAfter) {
        assertEquals(new Totals(12, 30, temporaryAfter),
                CurrentHitPoints.gainTemporary(new Totals(12, 30, temporary), gained));
    }

    @ParameterizedTest(name = "{0}/{1}, the maximum becoming {2}, dead {3}: {4}")
    @CsvSource(nullValues = "none", value = {
            // A higher maximum raises them by as much
            "40, 65, 71, false, 46",
            "65, 65, 71, false, 71",
            // A lower one caps them, and they stay where they are when it is still above them
            "65, 65, 60, false, 60",
            "40, 65, 60, false, 40",
            "40, 65, 65, false, 40",
            // A dead character stays at 0 whatever the maximum does
            "0, 65, 71, true, 0",
            "0, 65, 60, true, 0",
            // A character with no maximum yet keeps what it has, within the new one
            "12, none, 10, false, 10",
            "8, none, 10, false, 8",
    })
    @DisplayName("A new maximum raises hit points by as much as it rises, caps them when it falls, and leaves the dead at 0")
    void afterMaximum(int current, Integer oldMaximum, int newMaximum, boolean dead, int currentAfter) {
        assertEquals(currentAfter, CurrentHitPoints.afterMaximum(current, oldMaximum, newMaximum, dead));
    }

    @Test
    @DisplayName("Negative amounts, and hit points outside 0 to the maximum, are refused")
    void impossibleValues() {
        Totals totals = new Totals(10, 30, 0);
        assertThrows(IllegalArgumentException.class, () -> CurrentHitPoints.damage(totals, -1));
        assertThrows(IllegalArgumentException.class, () -> CurrentHitPoints.heal(totals, -1));
        assertThrows(IllegalArgumentException.class, () -> CurrentHitPoints.gainTemporary(totals, -1));
        assertThrows(IllegalArgumentException.class, () -> new Totals(31, 30, 0));
        assertThrows(IllegalArgumentException.class, () -> new Totals(-1, 30, 0));
        assertThrows(IllegalArgumentException.class, () -> new Totals(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Totals(10, 30, -2));
    }
}
