package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jack of All Trades (PHB p.54), Remarkable Athlete (PHB p.72) and passive checks (PHB p.175), typed out by hand
 * (D230).
 */
class AbilityChecksTest {

    @ParameterizedTest(name = "proficiency +{0}: Jack of All Trades +{1}, Remarkable Athlete +{2}")
    @CsvSource({"2, 1, 1", "3, 1, 2", "4, 2, 2", "5, 2, 3", "6, 3, 3"})
    @DisplayName("Jack of All Trades rounds half the proficiency bonus down, Remarkable Athlete up")
    void halves(int proficiencyBonus, int jack, int athlete) {
        assertThat(AbilityChecks.jackOfAllTrades(2, proficiencyBonus)).isEqualTo(jack);
        assertThat(AbilityChecks.remarkableAthlete(7, proficiencyBonus)).isEqualTo(athlete);
    }

    @Test
    @DisplayName("Jack of All Trades comes at Bard 2, Remarkable Athlete at Champion 7")
    void levels() {
        assertThat(AbilityChecks.jackOfAllTrades(1, 2)).isZero();
        assertThat(AbilityChecks.remarkableAthlete(6, 3)).isZero();
    }

    @Test
    @DisplayName("Half proficiency: none with proficiency, Remarkable Athlete only for STR, DEX and CON, the higher of the two")
    void halfProficiency() {
        assertThat(AbilityChecks.halfProficiency(Ability.DEX, true, 2, 7, 3)).isZero();
        assertThat(AbilityChecks.halfProficiency(Ability.DEX, false, 2, 7, 3)).isEqualTo(2);
        assertThat(AbilityChecks.halfProficiency(Ability.WIS, false, 0, 7, 3)).isZero();
        assertThat(AbilityChecks.halfProficiency(Ability.WIS, false, 2, 7, 3)).isEqualTo(1);
        assertThat(AbilityChecks.halfProficiency(Ability.CON, false, 0, 7, 3)).isEqualTo(2);
    }

    @Test
    @DisplayName("A passive score is 10 + the check's modifier + passive bonuses: WIS +2, Expertise at +3, Observant: 23")
    void passive() {
        int bonus = AbilityChecks.skillBonus(true, true, 3, 0);
        assertThat(bonus).isEqualTo(6);
        assertThat(AbilityChecks.passive(2, bonus, 5)).isEqualTo(23);
        assertThat(AbilityChecks.skillBonus(true, false, 3, 1)).isEqualTo(3);
        assertThat(AbilityChecks.skillBonus(false, false, 3, 1)).isEqualTo(1);
    }
}
