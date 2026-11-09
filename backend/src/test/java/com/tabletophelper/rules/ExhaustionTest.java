package com.tabletophelper.rules;

import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.RollMode;
import com.tabletophelper.rules.combat.SaveRules;
import com.tabletophelper.rules.combat.StandardActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exhaustion's six levels, each adding its effect to those below (PHB p.291, D183). */
class ExhaustionTest {

    @ParameterizedTest(name = "level {0}: checks {1}, speed 30 → {2}, attacks and saves {3}, HP max 45 → {4}, dies {5}")
    @CsvSource(delimiter = '|', textBlock = """
            # level | check disadvantage | speed 30 | attack and save disadvantage | max 45 | dies
              0     | false              | 30       | false                        | 45     | false
              1     | true               | 30       | false                        | 45     | false
              2     | true               | 15       | false                        | 45     | false
              3     | true               | 15       | true                         | 45     | false
              4     | true               | 15       | true                         | 22     | false
              5     | true               | 0        | true                         | 22     | false
              6     | true               | 0        | true                         | 22     | true
            """)
    @DisplayName("Each level adds its effect to those of the levels below (PHB p.291)")
    void levels(int level, boolean checks, int speed, boolean attacksAndSaves, int maximum, boolean dies) {
        assertEquals(checks, Exhaustion.abilityCheckDisadvantage(level));
        assertEquals(speed, Exhaustion.speed(30, level));
        assertEquals(attacksAndSaves, Exhaustion.attackDisadvantage(level));
        assertEquals(attacksAndSaves, Exhaustion.saveDisadvantage(level));
        assertEquals(maximum, Exhaustion.hitPointMaximum(45, level));
        assertEquals(dies, Exhaustion.dies(level));
        assertThat(Exhaustion.effects(level)).hasSize(level);
    }

    @Test
    @DisplayName("Halving rounds down (PHB p.7), and a maximum never drops below 1")
    void rounding() {
        assertEquals(12, Exhaustion.speed(25, 2));
        assertEquals(1, Exhaustion.hitPointMaximum(1, 4));
        assertEquals(1, Exhaustion.hitPointMaximum(3, 4));
    }

    @Test
    @DisplayName("A long rest lowers the level by one, not below 0 (PHB p.291); levels are kept between 0 and 6")
    void longRestAndClamp() {
        assertEquals(2, Exhaustion.afterLongRest(3));
        assertEquals(0, Exhaustion.afterLongRest(0));
        assertEquals(0, Exhaustion.clamp(null));
        assertEquals(6, Exhaustion.clamp(9));
        assertEquals(0, Exhaustion.clamp(-1));
    }

    @Test
    @DisplayName("The level travels among condition names as \"exhausted (N)\", the highest counting")
    void conditionName() {
        assertEquals("exhausted (3)", Exhaustion.conditionName(3));
        assertEquals("", Exhaustion.conditionName(0));
        assertEquals(3, Exhaustion.level(List.of("prone", "Exhausted (3)", "exhausted (1)")));
        assertEquals(0, Exhaustion.level(List.of("exhaustion", "exhausted")));
        assertEquals(0, Exhaustion.level(null));
    }

    @ParameterizedTest(name = "level {0}: attack roll {1}")
    @CsvSource({"2, NORMAL", "3, DISADVANTAGE", "5, DISADVANTAGE"})
    @DisplayName("From level 3 the attacker's rolls have disadvantage, named as a source")
    void attackRolls(int level, RollMode mode) {
        AttackRules.Setup setup = AttackRules.setup(List.of(),
                AttackRules.Side.of(List.of(Exhaustion.conditionName(level))), AttackRules.Side.NONE, true);
        assertEquals(mode, setup.rollMode());
        assertEquals(mode == RollMode.NORMAL ? "" : "disadvantage: attacker exhausted (" + level + ")",
                AttackRules.describe(setup.automatic()));
    }

    @Test
    @DisplayName("An exhausted target gives no advantage to attacks against it")
    void exhaustedTarget() {
        assertEquals(RollMode.NORMAL, AttackRules.setup(List.of(), AttackRules.Side.NONE,
                AttackRules.Side.of(List.of(Exhaustion.conditionName(5))), true).rollMode());
    }

    @ParameterizedTest(name = "level {0}: {1} save {2}")
    @CsvSource({"2, WIS, NORMAL", "3, WIS, DISADVANTAGE", "3, CON, DISADVANTAGE", "4, DEX, DISADVANTAGE"})
    @DisplayName("From level 3 every saving throw has disadvantage")
    void saves(int level, Ability ability, RollMode mode) {
        assertEquals(mode, SaveRules.rollMode(ability, List.of(Exhaustion.conditionName(level))));
    }

    @Test
    @DisplayName("Raging still gives a Strength save advantage, which cancels exhaustion's disadvantage (PHB p.173)")
    void rageCancels() {
        assertEquals(RollMode.NORMAL, SaveRules.rollMode(Ability.STR, List.of("raging", Exhaustion.conditionName(3))));
    }

    @Test
    @DisplayName("A Dodge loses its benefit once exhaustion brings the speed to 0 at level 5 (PHB p.192)")
    void dodgeAtSpeedZero() {
        assertThat(StandardActions.dodging(List.of(StandardActions.DODGING, Exhaustion.conditionName(4)))).isTrue();
        assertThat(StandardActions.dodging(List.of(StandardActions.DODGING, Exhaustion.conditionName(5)))).isFalse();
    }
}
