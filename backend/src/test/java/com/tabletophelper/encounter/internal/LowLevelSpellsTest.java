package com.tabletophelper.encounter.internal;

import com.tabletophelper.rules.combat.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules behind Counterspell, Dispel Magic, Blink and Mirror Image (D196). */
class LowLevelSpellsTest {

    @ParameterizedTest(name = "{0} duplicates need {1}+")
    @CsvSource({"3, 6", "2, 8", "1, 11"})
    @DisplayName("Mirror Image: the d20 an attack needs to target a duplicate falls with the duplicates left (PHB p.260)")
    void mirrorImageThresholds(int duplicates, int needed) {
        assertThat(DefensiveSpells.redirectThreshold(duplicates)).isEqualTo(needed);
    }

    @Test
    @DisplayName("Mirror Image: a duplicate is hit by a total of its AC or more, always by a critical, never by a 1")
    void duplicateHits() {
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.HIT, 12, 13, 13)).isTrue();
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.HIT, 11, 12, 13)).isFalse();
        // the attack missed the caster, but its total still beats the duplicate's lower AC
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.MISS, 9, 14, 12)).isTrue();
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.CRITICAL, 20, 5, 30)).isTrue();
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.MISS, 1, 30, 12)).isFalse();
        assertThat(DefensiveSpells.hitsDuplicate(Outcome.AUTOMATIC, null, null, 12)).isFalse();
    }

    @ParameterizedTest(name = "d20 {0} vanishes: {1}")
    @CsvSource({"1, false", "10, false", "11, true", "20, true"})
    @DisplayName("Blink: 11 or higher at the end of the turn sends the caster to the Ethereal Plane (PHB p.219)")
    void blinkRoll(int roll, boolean vanishes) {
        assertThat(DefensiveSpells.vanishes(roll)).isEqualTo(vanishes);
    }

    @Test
    @DisplayName("Counterspell reads the countered spell and its level from the cast's log text")
    void countersReadTheCast() {
        assertThat(SpellCounters.spellName("Fireball at level 5 (reaction).")).isEqualTo("Fireball");
        assertThat(SpellCounters.castLevel("Fireball at level 5 (reaction).", 3)).isEqualTo(5);
        assertThat(SpellCounters.spellName("Hold Person — the DM resolves it")).isEqualTo("Hold Person");
        assertThat(SpellCounters.castLevel("Hold Person.", 2)).isEqualTo(2);
        assertThat(SpellCounters.spellName("Tasha's Hideous Laughter (bonus action) (Quickened Spell)."))
                .isEqualTo("Tasha's Hideous Laughter");
        assertThat(SpellCounters.spellName("Fire Bolt.")).isEqualTo("Fire Bolt");
        assertThat(SpellCounters.castLevel("Fire Bolt.", 0)).isZero();
    }

    @Test
    @DisplayName("Shillelagh imbues only a club or a quarterstaff (PHB p.275)")
    void shillelaghWeapons() {
        assertThat(AttackService.shillelaghWeapon("Quarterstaff")).isTrue();
        assertThat(AttackService.shillelaghWeapon(" club ")).isTrue();
        assertThat(AttackService.shillelaghWeapon("Greatclub")).isFalse();
        assertThat(AttackService.shillelaghWeapon(null)).isFalse();
    }

    @Test
    @DisplayName("Counterspell and Dispel Magic are the spells resolved by SpellCounters")
    void handled() {
        assertThat(SpellCounters.handles("counterspell")).isTrue();
        assertThat(SpellCounters.handles("Dispel Magic")).isTrue();
        assertThat(SpellCounters.handles("Dispel Evil and Good")).isFalse();
    }
}
