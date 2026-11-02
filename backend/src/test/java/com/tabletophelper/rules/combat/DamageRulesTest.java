package com.tabletophelper.rules.combat;

import com.tabletophelper.rules.combat.DamageRules.Defence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cases beyond {@link DamageAndDyingGoldenTest}'s table. */
class DamageRulesTest {

    private static Vitals pc(int hp, int tempHp) {
        return new Vitals(hp, 20, tempHp, hp > 0 ? LifeState.ALIVE : LifeState.DYING, 0, 0, true);
    }

    @Test
    @DisplayName("Massive damage counts what is left after temporary HP: 35 through 5 temp onto 10/20 kills, 34 doesn't")
    void massiveDamageAfterTemporaryHitPoints() {
        VitalsChange killed = DamageRules.takeDamage(pc(10, 5), 35, false);
        assertEquals(LifeState.DEAD, killed.vitals().lifeState());
        assertTrue(killed.massiveDamage());

        VitalsChange downed = DamageRules.takeDamage(pc(10, 5), 34, false);
        assertEquals(LifeState.DYING, downed.vitals().lifeState());
        assertEquals(0, downed.vitals().tempHp());
    }

    @Test
    @DisplayName("Temporary HP that absorb all the damage at 0 HP add no death save failure")
    void temporaryHitPointsShieldTheDying() {
        Vitals dying = new Vitals(0, 20, 5, LifeState.DYING, 1, 1, true);
        VitalsChange change = DamageRules.takeDamage(dying, 5, true);
        assertEquals(new Vitals(0, 20, 0, LifeState.DYING, 1, 1, true), change.vitals());

        Vitals stable = new Vitals(0, 20, 5, LifeState.STABLE, 0, 0, true);
        assertEquals(LifeState.STABLE, DamageRules.takeDamage(stable, 3, false).vitals().lifeState());
    }

    @Test
    @DisplayName("Damage to the dead changes nothing")
    void damageToTheDead() {
        Vitals dead = new Vitals(0, 20, 0, LifeState.DEAD, 0, 3, true);
        assertEquals(VitalsChange.only(dead), DamageRules.takeDamage(dead, 50, true));
    }

    @Test
    @DisplayName("Healing stops at the HP maximum and leaves temporary HP alone")
    void healingStopsAtTheMaximum() {
        assertEquals(new Vitals(20, 20, 4, LifeState.ALIVE, 0, 0, true), DamageRules.heal(pc(15, 4), 9).vitals());
    }

    @Test
    @DisplayName("Healing a stable PC wakes it with the healing as its HP")
    void healingTheStable() {
        Vitals stable = new Vitals(0, 20, 0, LifeState.STABLE, 0, 0, true);
        VitalsChange change = DamageRules.heal(stable, 30);
        assertEquals(new Vitals(20, 20, 0, LifeState.ALIVE, 0, 0, true), change.vitals());
        assertTrue(change.removeUnconsciousFromDowned());
    }

    @Test
    @DisplayName("A dying PC can gain temporary HP; the dead can't")
    void temporaryHitPointsAtZero() {
        assertEquals(6, DamageRules.gainTempHp(pc(0, 0), 6).vitals().tempHp());
        Vitals dead = new Vitals(0, 20, 0, LifeState.DEAD, 0, 3, true);
        assertEquals(0, DamageRules.gainTempHp(dead, 6).vitals().tempHp());
    }

    @Test
    @DisplayName("Set HP keeps between 0 and the maximum; at 0 a PC drops to dying and a monster dies")
    void setHpBounds() {
        assertEquals(20, DamageRules.setHp(pc(5, 0), 99).vitals().hp());

        VitalsChange downed = DamageRules.setHp(pc(5, 2), 0);
        assertEquals(new Vitals(0, 20, 2, LifeState.DYING, 0, 0, true), downed.vitals());
        assertTrue(downed.droppedToZero() && downed.concentrationEnds() && downed.addUnconsciousAndProne());
        assertFalse(downed.died());

        Vitals monster = new Vitals(7, 10, 0, LifeState.ALIVE, 0, 0, false);
        VitalsChange killed = DamageRules.setHp(monster, -3);
        assertEquals(LifeState.DEAD, killed.vitals().lifeState());
        assertTrue(killed.died());

        Vitals stable = new Vitals(0, 20, 0, LifeState.STABLE, 0, 0, true);
        assertEquals(VitalsChange.only(stable), DamageRules.setHp(stable, 0));
    }

    @Test
    @DisplayName("Only the dying make death saves, and only with a d20 roll")
    void deathSavesAreForTheDying() {
        Vitals stable = new Vitals(0, 20, 0, LifeState.STABLE, 0, 0, true);
        assertEquals(VitalsChange.only(stable), DamageRules.deathSave(stable, 5));
        assertThrows(IllegalArgumentException.class, () -> DamageRules.deathSave(pc(0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> DamageRules.deathSave(pc(0, 0), 21));
    }

    @Test
    @DisplayName("A failed death save at 2 failures kills")
    void thirdFailureKills() {
        Vitals dying = new Vitals(0, 20, 0, LifeState.DYING, 1, 2, true);
        VitalsChange change = DamageRules.deathSave(dying, 9);
        assertEquals(new Vitals(0, 20, 0, LifeState.DEAD, 1, 3, true), change.vitals());
        assertTrue(change.died());
        assertFalse(change.massiveDamage());
    }

    @Test
    @DisplayName("No defences leave the damage as it is, and vulnerability alone doubles it")
    void noDefencesAndVulnerabilityAlone() {
        assertEquals(7, DamageRules.applyDefences(7, Set.of()));
        assertEquals(14, DamageRules.applyDefences(7, Set.of(Defence.VULNERABILITY)));
    }

    @Test
    @DisplayName("Damage, healing and temporary HP can't be negative")
    void negativeAmountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> DamageRules.takeDamage(pc(5, 0), -1, false));
        assertThrows(IllegalArgumentException.class, () -> DamageRules.heal(pc(5, 0), -1));
        assertThrows(IllegalArgumentException.class, () -> DamageRules.gainTempHp(pc(5, 0), -1));
        assertThrows(IllegalArgumentException.class, () -> DamageRules.applyDefences(-1, Set.of()));
    }

    @Test
    @DisplayName("Vitals refuse a state that can't happen")
    void impossibleVitals() {
        assertThrows(IllegalArgumentException.class, () -> new Vitals(0, 20, 0, LifeState.ALIVE, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(3, 20, 0, LifeState.DYING, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(0, 10, 0, LifeState.DYING, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(0, 20, 0, LifeState.DYING, 0, 4, true));
        assertThrows(IllegalArgumentException.class, () -> new Vitals(5, 0, 0, LifeState.ALIVE, 0, 0, true));
        assertThrows(NullPointerException.class, () -> new Vitals(5, 20, 0, null, 0, 0, true));
    }
}
