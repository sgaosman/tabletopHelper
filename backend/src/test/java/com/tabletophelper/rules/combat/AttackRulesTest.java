package com.tabletophelper.rules.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttackRulesTest {

    // ── Advantage and disadvantage ────────────────────────────────

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # sources                             | roll mode
              -                                   | NORMAL
              ADVANTAGE                           | ADVANTAGE
              DISADVANTAGE                        | DISADVANTAGE
              ADVANTAGE ADVANTAGE                 | ADVANTAGE
              ADVANTAGE DISADVANTAGE              | NORMAL
              ADVANTAGE DISADVANTAGE DISADVANTAGE | NORMAL
              NORMAL DISADVANTAGE                 | DISADVANTAGE
            """)
    @DisplayName("Sources of advantage and disadvantage combine into one roll mode; any of both cancel (PHB p.173)")
    void sourcesCombine(String sources, RollMode expected) {
        assertEquals(expected, RollMode.combine(modes(sources)));
    }

    // ── Hitting ───────────────────────────────────────────────────

    @ParameterizedTest(name = "d20 {0} + {1} vs AC {2}, a hit is critical: {3} → {4}")
    @CsvSource({
            "1,  20, 10, false, MISS",      // a natural 1 misses whatever the bonus
            "1,  20, 10, true,  MISS",
            "20, -5, 30, false, CRITICAL",  // a natural 20 hits whatever the AC, as a critical hit
            "10, 5,  15, false, HIT",       // reaching the AC hits
            "10, 4,  15, false, MISS",
            "10, 5,  15, true,  CRITICAL",  // a hit on an unconscious or paralyzed target within 5 ft
            "10, 4,  15, true,  MISS",      // which still has to hit
    })
    @DisplayName("A natural 1 misses and a natural 20 hits; otherwise the total must reach the AC (PHB p.194)")
    void outcome(int d20, int attackBonus, int armourClass, boolean hitIsCritical, Outcome expected) {
        assertEquals(expected, AttackRules.outcome(d20, attackBonus, armourClass, hitIsCritical));
    }

    @Test
    @DisplayName("The roll must be a d20's")
    void rollMustBeAD20s() {
        assertThrows(IllegalArgumentException.class, () -> AttackRules.outcome(0, 5, 10, false));
        assertThrows(IllegalArgumentException.class, () -> AttackRules.outcome(21, 5, 10, false));
    }

    // ── Downed targets ────────────────────────────────────────────

    @ParameterizedTest(name = "{0}, within 5 ft: {1}, the attack has {2} → {3}, a hit is critical: {4}")
    @CsvSource(delimiter = '|', textBlock = """
            # target conditions | within 5 ft | attack sources | roll mode    | hit is critical
            # Unconscious or paralyzed within 5 ft: advantage, and any hit is a critical hit
              unconscious prone | true        | -              | ADVANTAGE    | true
              paralyzed         | true        | -              | ADVANTAGE    | true
              Unconscious       | true        | -              | ADVANTAGE    | true
            # Beyond 5 ft an unconscious target is also prone, so the advantage cancels; a paralyzed one gives it
              unconscious prone | false       | -              | NORMAL       | false
              unconscious       | false       | -              | NORMAL       | false
              paralyzed         | false       | -              | ADVANTAGE    | false
              unconscious       | false       | ADVANTAGE      | NORMAL       | false
            # The attack's own disadvantage cancels the advantage, and a hit is still critical
              unconscious prone | true        | DISADVANTAGE   | NORMAL       | true
            # Other targets: the attack's own sources decide
              -                 | true        | ADVANTAGE      | ADVANTAGE    | false
              -                 | false       | -              | NORMAL       | false
              poisoned          | true        | DISADVANTAGE   | DISADVANTAGE | false
            # A dodging target gives disadvantage, unless incapacitated or held at speed 0 (PHB p.192)
              dodging           | true        | -              | DISADVANTAGE | false
              Dodging           | false       | ADVANTAGE      | NORMAL       | false
              dodging stunned   | true        | -              | NORMAL       | false
              dodging grappled  | true        | -              | NORMAL       | false
              dodging restrained | true       | -              | NORMAL       | false
              dodging paralyzed | true        | -              | ADVANTAGE    | true
            """)
    @DisplayName("Attacks on unconscious, paralyzed and dodging targets (PHB appendix A, p.192)")
    void downedTargets(String conditions, boolean withinFiveFeet, String sources, RollMode rollMode,
                       boolean hitIsCritical) {
        assertEquals(new AttackRules.Setup(rollMode, hitIsCritical),
                AttackRules.setup(modes(sources), names(conditions), withinFiveFeet));
    }

    private static List<String> names(String spaced) {
        return "-".equals(spaced) ? List.of() : Arrays.asList(spaced.split("\\s+"));
    }

    private static List<RollMode> modes(String spaced) {
        return names(spaced).stream().map(RollMode::valueOf).toList();
    }
}
