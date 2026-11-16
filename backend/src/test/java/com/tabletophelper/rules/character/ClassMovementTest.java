package com.tabletophelper.rules.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast Movement (PHB p.49) and Unarmored Movement (PHB p.78), with the Monk table's Unarmored Movement column typed out
 * for every level (D230).
 */
class ClassMovementTest {

    @Test
    @DisplayName("Fast Movement: +10 from Barbarian 5 unless in heavy armour, nothing before")
    void fastMovement() {
        assertThat(ClassMovement.fastMovement(4, false)).isZero();
        assertThat(ClassMovement.fastMovement(5, false)).isEqualTo(10);
        assertThat(ClassMovement.fastMovement(20, false)).isEqualTo(10);
        assertThat(ClassMovement.fastMovement(5, true)).isZero();
        assertThat(ClassMovement.fastMovement(0, false)).isZero();
    }

    @ParameterizedTest(name = "Monk {0}: +{1} ft")
    @CsvSource({"0, 0", "1, 0", "2, 10", "3, 10", "4, 10", "5, 10", "6, 15", "7, 15", "8, 15", "9, 15", "10, 20",
            "11, 20", "12, 20", "13, 20", "14, 25", "15, 25", "16, 25", "17, 25", "18, 30", "19, 30", "20, 30"})
    @DisplayName("Unarmored Movement by Monk level, the Monk table (PHB p.77)")
    void unarmoredMovementTable(int monkLevel, int bonus) {
        assertThat(ClassMovement.unarmoredMovement(monkLevel, false, false)).isEqualTo(bonus);
    }

    @Test
    @DisplayName("Unarmored Movement needs no armour and no shield")
    void unarmoredMovementNeedsNoArmour() {
        assertThat(ClassMovement.unarmoredMovement(10, true, false)).isZero();
        assertThat(ClassMovement.unarmoredMovement(10, false, true)).isZero();
    }
}
