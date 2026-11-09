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
    }

    @Test
    @DisplayName("A critical range below 20 makes those rolls critical hits, which hit whatever the AC (PHB p.72)")
    void critRange() {
        // A Champion's 19 is a critical hit and so a hit, whatever the AC; an 18 isn't; a 1 still misses (PHB p.72)
        assertEquals(Outcome.CRITICAL, AttackRules.outcome(19, 0, 30, false, 19));
        assertEquals(Outcome.MISS, AttackRules.outcome(18, 0, 30, false, 19));
        assertEquals(Outcome.CRITICAL, AttackRules.outcome(18, 0, 30, false, 18));
        assertEquals(Outcome.MISS, AttackRules.outcome(1, 30, 2, false, 18));
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
              dodging grappled  | true        | -              | NORMAL       | false
            # A stunned, restrained or paralyzed target loses the Dodge and gives advantage itself
              dodging stunned   | true        | -              | ADVANTAGE    | false
              dodging restrained | true       | -              | ADVANTAGE    | false
              dodging paralyzed | true        | -              | ADVANTAGE    | true
            """)
    @DisplayName("Attacks on unconscious, paralyzed and dodging targets (PHB appendix A, p.192)")
    void downedTargets(String conditions, boolean withinFiveFeet, String sources, RollMode rollMode,
                       boolean hitIsCritical) {
        AttackRules.Setup setup = AttackRules.setup(modes(sources), names(conditions), withinFiveFeet);
        assertEquals(rollMode, setup.rollMode());
        assertEquals(hitIsCritical, setup.hitIsCritical());
    }

    // ── Conditions on both sides (PHB appendix A) ─────────────────

    @ParameterizedTest(name = "attacker {0} ({1}), target {2} ({3}), within 5 ft: {4}, own {5} → {6}: {7}")
    @CsvSource(delimiter = '|', textBlock = """
            # attacker   | senses     | target      | senses     | 5 ft  | own          | roll mode    | automatic sources
            # Each attacker condition alone
              blinded    | -          | -           | -          | true  | -            | DISADVANTAGE | disadvantage: attacker blinded
              poisoned   | -          | -           | -          | true  | -            | DISADVANTAGE | disadvantage: attacker poisoned
              restrained | -          | -           | -          | true  | -            | DISADVANTAGE | disadvantage: attacker restrained
              prone      | -          | -           | -          | false | -            | DISADVANTAGE | disadvantage: attacker prone
              invisible  | -          | -           | -          | true  | -            | ADVANTAGE    | advantage: attacker invisible
              frightened | -          | -           | -          | true  | -            | NORMAL       | -
              grappled   | -          | -           | -          | true  | -            | NORMAL       | -
            # Each target condition alone
              -          | -          | blinded     | -          | true  | -            | ADVANTAGE    | advantage: target blinded
              -          | -          | restrained  | -          | false | -            | ADVANTAGE    | advantage: target restrained
              -          | -          | stunned     | -          | false | -            | ADVANTAGE    | advantage: target stunned
              -          | -          | paralyzed   | -          | false | -            | ADVANTAGE    | advantage: target paralyzed
              -          | -          | petrified   | -          | true  | -            | ADVANTAGE    | advantage: target petrified
              -          | -          | invisible   | -          | true  | -            | DISADVANTAGE | disadvantage: target invisible
              -          | -          | poisoned    | -          | true  | -            | NORMAL       | -
              -          | -          | grappled    | -          | true  | -            | NORMAL       | -
            # Prone: advantage from within 5 ft, disadvantage from beyond (PHB p.292)
              -          | -          | prone       | -          | true  | -            | ADVANTAGE    | advantage: target prone within 5 ft
              -          | -          | prone       | -          | false | -            | DISADVANTAGE | disadvantage: target prone beyond 5 ft
            # Unconscious is prone too, counted once whether or not prone is also listed
              -          | -          | unconscious | -          | true  | -            | ADVANTAGE    | advantage: target unconscious, target prone within 5 ft
              -          | -          | unconscious prone | -    | true  | -            | ADVANTAGE    | advantage: target unconscious, target prone within 5 ft
              -          | -          | unconscious | -          | false | -            | NORMAL       | advantage: target unconscious; disadvantage: target prone beyond 5 ft
              -          | -          | unconscious prone | -    | false | -            | NORMAL       | advantage: target unconscious; disadvantage: target prone beyond 5 ft
            # Advantage and disadvantage cancel, however many of each (PHB p.173)
              poisoned   | -          | restrained  | -          | true  | -            | NORMAL       | advantage: target restrained; disadvantage: attacker poisoned
              poisoned prone | -      | stunned     | -          | true  | -            | NORMAL       | advantage: target stunned; disadvantage: attacker poisoned, attacker prone
              invisible  | -          | invisible   | -          | true  | -            | NORMAL       | advantage: attacker invisible; disadvantage: target invisible
              blinded    | -          | blinded     | -          | true  | -            | NORMAL       | advantage: target blinded; disadvantage: attacker blinded
              -          | -          | restrained  | -          | true  | DISADVANTAGE | NORMAL       | advantage: target restrained
              poisoned   | -          | -           | -          | true  | ADVANTAGE    | NORMAL       | disadvantage: attacker poisoned
              restrained | -          | restrained  | -          | true  | ADVANTAGE    | NORMAL       | advantage: target restrained; disadvantage: attacker restrained
            # Blindsight: ignores its own blindness and the target's invisibility; a target's denies invisible advantage
              blinded    | blindsight | -           | -          | true  | -            | NORMAL       | -
              -          | blindsight | invisible   | -          | true  | -            | NORMAL       | -
              blinded    | blindsight | invisible   | -          | true  | -            | NORMAL       | -
              invisible  | -          | -           | blindsight | true  | -            | NORMAL       | -
              -          | -          | blinded     | blindsight | true  | -            | NORMAL       | -
            # Truesight: sees the invisible, but it is sight, so a blinded creature loses it (MM p.9)
              -          | truesight  | invisible   | -          | true  | -            | NORMAL       | -
              blinded    | truesight  | -           | -          | true  | -            | DISADVANTAGE | disadvantage: attacker blinded
              blinded    | truesight  | invisible   | -          | true  | -            | DISADVANTAGE | disadvantage: attacker blinded, target invisible
              invisible  | -          | -           | truesight  | true  | -            | NORMAL       | -
              invisible  | -          | blinded     | truesight  | true  | -            | ADVANTAGE    | advantage: attacker invisible, target blinded
            # Senses change nothing else
              poisoned   | blindsight | restrained  | truesight  | true  | -            | NORMAL       | advantage: target restrained; disadvantage: attacker poisoned
            # A Dodge needs the dodger to perceive the attacker (PHB p.192)
              invisible  | -          | dodging     | -          | true  | -            | ADVANTAGE    | advantage: attacker invisible
              invisible  | -          | dodging     | blindsight | true  | -            | DISADVANTAGE | disadvantage: target dodging
              -          | -          | dodging blinded | -      | true  | -            | ADVANTAGE    | advantage: target blinded
            """)
    @DisplayName("The attacker's and the target's conditions and senses add advantage and disadvantage (PHB p.290-292)")
    void conditionsOnBothSides(String attackerConditions, String attackerSenses, String targetConditions,
                               String targetSenses, boolean withinFiveFeet, String own, RollMode rollMode,
                               String automatic) {
        AttackRules.Setup setup = AttackRules.setup(modes(own),
                new AttackRules.Side(names(attackerConditions), senses(attackerSenses)),
                new AttackRules.Side(names(targetConditions), senses(targetSenses)), withinFiveFeet);
        assertEquals(rollMode, setup.rollMode());
        assertEquals("-".equals(automatic) ? "" : automatic, AttackRules.describe(setup.automatic()));
    }

    @ParameterizedTest(name = "{0} within 5 ft: {1} → a hit is critical: {2}")
    @CsvSource({
            "unconscious, true,  true",
            "paralyzed,   true,  true",
            "unconscious, false, false",
            "petrified,   true,  false",  // advantage, but no automatic critical hit (PHB p.291)
            "stunned,     true,  false",
            "restrained,  true,  false",
            "prone,       true,  false",
    })
    @DisplayName("Only an unconscious or paralyzed target is critically hit from within 5 ft (PHB p.291-292)")
    void criticalHits(String condition, boolean withinFiveFeet, boolean critical) {
        assertEquals(critical, AttackRules.setup(List.of(), AttackRules.Side.NONE,
                AttackRules.Side.of(List.of(condition)), withinFiveFeet).hitIsCritical());
    }

    @Test
    @DisplayName("Condition names match whatever their case and surrounding spaces, on either side")
    void conditionNamesIgnoreCase() {
        AttackRules.Setup setup = AttackRules.setup(List.of(), AttackRules.Side.of(List.of(" Poisoned ")),
                AttackRules.Side.of(List.of("RESTRAINED", "Invisible")), true);
        assertEquals(RollMode.NORMAL, setup.rollMode());
        assertEquals("advantage: target restrained; disadvantage: attacker poisoned, target invisible",
                AttackRules.describe(setup.automatic()));
    }

    @ParameterizedTest(name = "\"{0}\" → blindsight {1}, truesight {2}")
    @CsvSource(delimiter = '|', textBlock = """
            # senses text                                                           | blindsight | truesight
              {"special":["blindsight 30 ft. (blind beyond this radius)"],"passive_perception":10} | true | false
              truesight 120 ft.                                                       | false      | true
              Blindsight 10 ft., Truesight 60 ft.                                     | true       | true
              darkvision 60 ft.                                                       | false      | false
              -                                                                       | false      | false
            """)
    @DisplayName("A stat block's senses text gives blindsight and truesight, whatever its range")
    void sensesFromText(String text, boolean blindsight, boolean truesight) {
        assertEquals(new AttackRules.Senses(blindsight, truesight), AttackRules.Senses.of("-".equals(text) ? null : text));
    }

    private static AttackRules.Senses senses(String spaced) {
        List<String> names = names(spaced);
        return new AttackRules.Senses(names.contains("blindsight"), names.contains("truesight"));
    }

    private static List<String> names(String spaced) {
        return "-".equals(spaced) ? List.of() : Arrays.asList(spaced.split("\\s+"));
    }

    private static List<RollMode> modes(String spaced) {
        return names(spaced).stream().map(RollMode::valueOf).toList();
    }

    @Test
    @DisplayName("Attacks against a creature that attacked recklessly have advantage (PHB p.48); a Dodge cancels it")
    void recklessTarget() {
        assertEquals(RollMode.ADVANTAGE,
                AttackRules.setup(List.of(), List.of(ClassFeatures.RECKLESS), true).rollMode());
        assertEquals(RollMode.NORMAL,
                AttackRules.setup(List.of(), List.of(ClassFeatures.RECKLESS, StandardActions.DODGING), true).rollMode());
        assertEquals(RollMode.NORMAL,
                AttackRules.setup(List.of(RollMode.DISADVANTAGE), List.of(ClassFeatures.RECKLESS), true).rollMode());
        assertEquals("advantage: target attacked recklessly; disadvantage: target dodging",
                AttackRules.describe(AttackRules.setup(List.of(),
                        List.of(ClassFeatures.RECKLESS, StandardActions.DODGING), true).automatic()));
    }
}
