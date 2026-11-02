package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.DamageRules.Defence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D161's damage and dying table (D172), typed out here from the 2014 PHB, chapter 9 ("Damage and Healing",
 * "Dropping to 0 Hit Points", "Death Saving Throws"), independently of {@link DamageRules}. Each row is one case:
 * where a creature starts, one event, and what that leaves.
 */
class DamageAndDyingGoldenTest {

    enum Who { PC, MONSTER }

    /** CRIT_DAMAGE is damage from a critical hit; a DEATH_SAVE's amount is the d20 as rolled. */
    enum Event { DAMAGE, CRIT_DAMAGE, HEAL, TEMP_HP, SET_HP, DEATH_SAVE }

    // hp is current/maximum, saves is death save successes/failures, and flags names the VitalsChange flags set
    @ParameterizedTest(name = "{0} at {1} HP, {2} temp, {3} {4}: {5} {6}")
    @CsvSource(delimiter = '|', textBlock = """
            # who     | hp    | temp | state  | saves | event       | amount | hp | temp | state  | saves | flags
            # Temporary HP absorb damage first
              PC      | 10/20 | 5    | ALIVE  | 0/0   | DAMAGE      | 8      | 7  | 0    | ALIVE  | 0/0   | -
            # Dropping to 0: a PC falls unconscious and prone and starts dying, and its concentration ends
              PC      | 10/20 | 0    | ALIVE  | 0/0   | DAMAGE      | 25     | 0  | 0    | DYING  | 0/0   | droppedToZero concentrationEnds addUnconsciousAndProne
            # Massive damage: 20 left over after reaching 0 is at least the HP maximum, so the PC dies outright
              PC      | 10/20 | 0    | ALIVE  | 0/0   | DAMAGE      | 30     | 0  | 0    | DEAD   | 0/0   | droppedToZero died massiveDamage concentrationEnds
            # Damage at 0 HP is one death save failure, or two from a critical hit; the third failure kills
              PC      | 0/20  | 0    | DYING  | 0/1   | DAMAGE      | 5      | 0  | 0    | DYING  | 0/2   | -
              PC      | 0/20  | 0    | DYING  | 0/1   | CRIT_DAMAGE | 5      | 0  | 0    | DEAD   | 0/3   | died
            # A stable PC that takes damage starts dying again
              PC      | 0/20  | 0    | STABLE | 0/0   | DAMAGE      | 3      | 0  | 0    | DYING  | 0/1   | -
            # Damage at 0 HP that is at least the HP maximum kills outright
              PC      | 0/20  | 0    | DYING  | 0/0   | DAMAGE      | 20     | 0  | 0    | DEAD   | 0/0   | died massiveDamage
            # A monster that drops to 0 is dead
              MONSTER | 5/10  | 0    | ALIVE  | 0/0   | DAMAGE      | 5      | 0  | 0    | DEAD   | 0/0   | droppedToZero died concentrationEnds
            # Temporary HP don't stack: gaining 5 then 2 leaves the larger amount
              PC      | 10/20 | 3    | ALIVE  | 0/0   | TEMP_HP     | 5      | 10 | 5    | ALIVE  | 0/0   | -
              PC      | 10/20 | 5    | ALIVE  | 0/0   | TEMP_HP     | 2      | 10 | 5    | ALIVE  | 0/0   | -
            # Healing a dying PC: it regains the HP and consciousness and both counts reset, but it stays prone
              PC      | 0/20  | 0    | DYING  | 0/2   | HEAL        | 5      | 5  | 0    | ALIVE  | 0/0   | removeUnconsciousFromDowned
            # The dead regain no HP from healing; only a revival (Set HP) brings them back
              PC      | 0/20  | 0    | DEAD   | 0/3   | HEAL        | 10     | 0  | 0    | DEAD   | 0/3   | -
              MONSTER | 0/10  | 0    | DEAD   | 0/0   | HEAL        | 10     | 0  | 0    | DEAD   | 0/0   | -
              PC      | 0/20  | 0    | DEAD   | 0/3   | SET_HP      | 1      | 1  | 0    | ALIVE  | 0/0   | removeUnconsciousFromDowned
              MONSTER | 0/10  | 0    | DEAD   | 0/0   | SET_HP      | 1      | 1  | 0    | ALIVE  | 0/0   | -
            # Death saves: 10 or higher succeeds and the third success makes the PC stable with both counts reset;
            # a 20 brings it back with 1 HP; a 1 counts as two failures
              PC      | 0/20  | 0    | DYING  | 2/0   | DEATH_SAVE  | 12     | 0  | 0    | STABLE | 0/0   | -
              PC      | 0/20  | 0    | DYING  | 2/0   | DEATH_SAVE  | 20     | 1  | 0    | ALIVE  | 0/0   | removeUnconsciousFromDowned
              PC      | 0/20  | 0    | DYING  | 2/0   | DEATH_SAVE  | 1      | 0  | 0    | DYING  | 2/2   | -
            """)
    @DisplayName("Hit points, dropping to 0 and death saves (PHB p.197-198)")
    void hitPointsAndDying(Who who, String hp, int temp, LifeState state, String saves, Event event, int amount,
                           int expectedHp, int expectedTemp, LifeState expectedState, String expectedSaves,
                           String expectedFlags) {
        int[] hpAndMax = pair(hp);
        int[] successesAndFailures = pair(saves);
        Vitals before = new Vitals(hpAndMax[0], hpAndMax[1], temp, state,
                successesAndFailures[0], successesAndFailures[1], who == Who.PC);

        VitalsChange change = switch (event) {
            case DAMAGE -> DamageRules.takeDamage(before, amount, false);
            case CRIT_DAMAGE -> DamageRules.takeDamage(before, amount, true);
            case HEAL -> DamageRules.heal(before, amount);
            case TEMP_HP -> DamageRules.gainTempHp(before, amount);
            case SET_HP -> DamageRules.setHp(before, amount);
            case DEATH_SAVE -> DamageRules.deathSave(before, amount);
        };

        Vitals after = change.vitals();
        assertAll(
                () -> assertEquals(expectedHp, after.hp(), "hp"),
                () -> assertEquals(before.maxHp(), after.maxHp(), "hp maximum"),
                () -> assertEquals(expectedTemp, after.tempHp(), "temporary hp"),
                () -> assertEquals(expectedState, after.lifeState(), "life state"),
                () -> assertEquals(expectedSaves, after.deathSaveSuccesses() + "/" + after.deathSaveFailures(),
                        "death saves (successes/failures)"),
                () -> assertEquals(before.playerCharacter(), after.playerCharacter(), "PC or monster"),
                () -> assertEquals(names(expectedFlags), flagsSet(change), "flags"));
    }

    // The defences are the target's against the damage's type, say fire: resistant to fire, immune to fire
    @ParameterizedTest(name = "{0} damage, saved for half: {1}, defences: {2} → {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # damage | saved for half | defences                 | taken
            # Resistance halves, rounding down with no minimum, so half of 1 is 0
              7      | false          | RESISTANCE               | 3
              1      | false          | RESISTANCE               | 0
            # Resistance halves first, rounding down, and then vulnerability doubles; immunity makes it 0
              7      | false          | RESISTANCE VULNERABILITY | 6
              7      | false          | IMMUNITY VULNERABILITY   | 0
            # A successful save for half rounds down the same way
              7      | true           | -                        | 3
              1      | true           | -                        | 0
            """)
    @DisplayName("Resistance, vulnerability, immunity and saves for half (PHB p.197)")
    void damageTypesAndSavesForHalf(int damage, boolean savedForHalf, String defences, int expected) {
        int afterSave = savedForHalf ? DamageRules.halved(damage) : damage;
        assertEquals(expected, DamageRules.applyDefences(afterSave, defences(defences)));
    }

    private static int[] pair(String slashed) {
        String[] parts = slashed.split("/");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static Set<String> names(String spaced) {
        return "-".equals(spaced) ? Set.of() : new TreeSet<>(Arrays.asList(spaced.split("\\s+")));
    }

    private static Set<Defence> defences(String spaced) {
        Set<Defence> defences = EnumSet.noneOf(Defence.class);
        names(spaced).forEach(name -> defences.add(Defence.valueOf(name)));
        return defences;
    }

    private static Set<String> flagsSet(VitalsChange change) {
        Set<String> set = new TreeSet<>();
        if (change.droppedToZero()) set.add("droppedToZero");
        if (change.died()) set.add("died");
        if (change.massiveDamage()) set.add("massiveDamage");
        if (change.concentrationEnds()) set.add("concentrationEnds");
        if (change.addUnconsciousAndProne()) set.add("addUnconsciousAndProne");
        if (change.removeUnconsciousFromDowned()) set.add("removeUnconsciousFromDowned");
        return set;
    }
}
