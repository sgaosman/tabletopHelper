package com.tabletophelper.rules.character;

import com.tabletophelper.rules.character.CurrentHitPoints.Totals;
import com.tabletophelper.rules.combat.LifeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Write-back's rules (D166), typed out here independently of {@link WriteBack}: each total gets the participant's
 * change since it joined, applied to the sheet as it stands and kept between 0 and the sheet's limit, so two
 * encounters' changes add up; temporary hit points are what the participant had left; and death, Dying and Stable
 * decide the hit points.
 */
class WriteBackTest {

    @ParameterizedTest(name = "sheet {0} of {1}, joined {2}, ended {3}: {4}")
    @CsvSource({
            // The sheet stood still: the participant's final total
            "50, 50, 50, 20, 20",
            "2, 4, 2, 3, 3",
            // The sheet changed meanwhile, as in a second encounter or a heal on the sheet: the change adds to it
            "30, 50, 50, 20, 0",
            "40, 50, 30, 25, 35",
            "10, 50, 20, 30, 20",
            // Kept between 0 and the limit
            "0, 50, 50, 47, 0",
            "48, 50, 20, 30, 50",
            "1, 4, 4, 0, 0",
            // Nothing changed
            "37, 50, 50, 50, 37",
    })
    @DisplayName("A total gets the participant's change since it joined, kept between 0 and the sheet's limit")
    void total(int sheet, int limit, int joined, int ended, int after) {
        assertEquals(after, WriteBack.total(sheet, limit, joined, ended));
    }

    @Test
    @DisplayName("Two encounters' changes add up, stopping at 0: 50 HP after -50, 0 and -3 is 0")
    void twoEncountersAddUp() {
        int sheet = 50;
        sheet = WriteBack.total(sheet, 50, 50, 0);
        sheet = WriteBack.total(sheet, 50, 50, 50);
        sheet = WriteBack.total(sheet, 50, 50, 47);
        assertEquals(0, sheet);

        // A slot spent in each of two encounters both started with leaves two spent
        int used = WriteBack.total(0, 4, 0, 1);
        assertEquals(2, WriteBack.total(used, 4, 0, 1));
    }

    @Test
    @DisplayName("A Psionic Energy die regained in combat through Psionic Recovery reaches the sheet as a gain since "
            + "joining, and a die spent and regained changes nothing (D127)")
    void aDieRegainedInCombat() {
        // The dice: the sheet stands at 1 of 4 as the participant joined with 1, which ended with 2
        assertEquals(2, WriteBack.total(1, 4, 1, 2));
        // The recovery: joined with 1 of 1, ended with 0
        assertEquals(0, WriteBack.total(1, 1, 1, 0));
        // Joined with every die, spent one and regained it: nothing changes, and the sheet stays at its maximum
        assertEquals(4, WriteBack.total(4, 4, 4, 4));
    }

    @Test
    @DisplayName("A character who ended alive gets the change in its hit points and the temporary hit points it had left")
    void alive() {
        assertEquals(new WriteBack.Outcome(new Totals(32, 45, 4), false),
                WriteBack.hitPoints(new Totals(40, 45, 7), false, 45, 37, 4, LifeState.ALIVE));
        // A heal on the sheet during the fight stays
        assertEquals(new WriteBack.Outcome(new Totals(45, 45, 0), false),
                WriteBack.hitPoints(new Totals(45, 45, 0), false, 30, 40, 0, LifeState.ALIVE));
    }

    @ParameterizedTest
    @EnumSource(value = LifeState.class, names = {"DYING", "STABLE"})
    @DisplayName("A character still Dying or Stable is written back alive at 0 hit points, whatever the sheet gained")
    void dyingOrStable(LifeState lifeState) {
        assertEquals(new WriteBack.Outcome(new Totals(0, 45, 0), false),
                WriteBack.hitPoints(new Totals(45, 45, 0), false, 30, 0, 0, lifeState));
        assertEquals(new WriteBack.Outcome(new Totals(0, 45, 3), false),
                WriteBack.hitPoints(new Totals(20, 45, 0), false, 20, 0, 3, lifeState));
    }

    @Test
    @DisplayName("A character who died is dead at 0 hit points with none temporary")
    void dead() {
        assertEquals(new WriteBack.Outcome(new Totals(0, 45, 0), true),
                WriteBack.hitPoints(new Totals(45, 45, 5), false, 45, 0, 2, LifeState.DEAD));
    }

    @Test
    @DisplayName("A character its sheet already marks dead stays at 0, even from an encounter it ended alive in")
    void alreadyDead() {
        assertEquals(new WriteBack.Outcome(new Totals(0, 45, 0), true),
                WriteBack.hitPoints(new Totals(0, 45, 0), true, 30, 40, 6, LifeState.ALIVE));
    }
}
