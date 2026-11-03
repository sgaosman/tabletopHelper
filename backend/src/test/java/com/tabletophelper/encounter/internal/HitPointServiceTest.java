package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterChanged;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.DeathSaveRequest;
import com.tabletophelper.encounter.dto.HealRequest;
import com.tabletophelper.encounter.dto.SetHpRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Hit points by hand (D160, D161): the DM's damage, healing and Set HP / revive, and death saves. From the old
 * CombatServiceTest: the damage flow 10.1 to 10.6, healing 10.7 to 10.9 (10.9 changed by D161: healing no
 * longer revives the dead, Set HP does), death saves 10.10 to 10.13, concentration 10.22 to 10.25 on the
 * DM's damage, and the lifecycle and locking cases (Paused, non-member, lock then announce). Every damage
 * case also runs on every other path in EffectApplierTest.
 */
class HitPointServiceTest {

    private CombatFixture f;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
    }

    private void damage(EncounterParticipant target, int amount, String type) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        request.setDamageType(type);
        f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);
    }

    private void heal(EncounterParticipant target, int amount) {
        HealRequest request = new HealRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        f.hitPoints.applyHealing(f.encounterId(), request, null, f.dmUserId);
    }

    private void deathSave(EncounterParticipant pc, int d20) {
        f.dice.script(d20);
        DeathSaveRequest request = new DeathSaveRequest();
        request.setParticipantId(pc.getId());
        f.hitPoints.rollDeathSave(f.encounterId(), request, f.dmUserId);
    }

    private void setHp(EncounterParticipant target, int hp, Integer tempHp) {
        SetHpRequest request = new SetHpRequest();
        request.setTargetId(target.getId());
        request.setHpCurrent(hp);
        request.setHpTemp(tempHp);
        f.hitPoints.setHp(f.encounterId(), request, f.dmUserId);
    }

    // ── Damage (10.1 to 10.6) ────────────────────────────────────

    @Test
    @DisplayName("10.1 Normal damage reduces HP, and the log says who dealt it")
    void normalDamageReducesHp() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        damage(goblin, 3, "slashing");

        assertEquals(4, goblin.getHpCurrent());
        assertEquals(LifeState.ALIVE, goblin.getLifeState());
        assertEquals(List.of("DM deals 3 damage to Goblin (slashing)"), f.descriptions());
    }

    @Test
    @DisplayName("10.2 Temp HP absorbs damage before real HP")
    void tempHpAbsorbsDamage() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setHpTemp(10);

        damage(fighter, 15, null);

        assertEquals(0, fighter.getHpTemp());
        assertEquals(25, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("10.3 A monster at 0 HP is Dead; a PC at 0 HP is Dying, unconscious and prone, with its death saves reset")
    void damageToZeroHp() {
        EncounterParticipant goblin = f.monster("Goblin", 3, 15);
        EncounterParticipant pc = f.player("Fighter", 3, 18);

        damage(goblin, 5, null);
        damage(pc, 5, null);

        assertEquals(0, goblin.getHpCurrent());
        assertEquals(LifeState.DEAD, goblin.getLifeState());
        assertEquals(0, pc.getHpCurrent());
        assertEquals(LifeState.DYING, pc.getLifeState());
        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
        assertEquals(List.of("unconscious", "prone"), CombatFixture.conditionNames(pc));
    }

    @Test
    @DisplayName("10.4 Damage of at least the HP maximum to a downed PC kills it")
    void massiveDamageInstantDeath() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 0);

        damage(pc, 30, null);

        assertEquals(LifeState.DEAD, pc.getLifeState());
        assertTrue(f.descriptions().contains("Fighter takes damage of at least their hit point maximum and dies"));
    }

    @Test
    @DisplayName("10.5 Damage to a Dying PC adds a death save failure, and the third kills it")
    void damageToDyingPcAddsFailure() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 1);

        damage(pc, 5, null);
        assertEquals(2, pc.getDeathSaveFailures());
        damage(pc, 5, null);

        assertEquals(3, pc.getDeathSaveFailures());
        assertEquals(LifeState.DEAD, pc.getLifeState());
        assertEquals(1, f.entries(CombatActionType.KILL).size());
    }

    @Test
    @DisplayName("10.6 Damage to a concentrating creature makes a concentration check")
    void damageTriggersConcentrationCheck() {
        EncounterParticipant wizard = f.player("Wizard", 30, 12);
        wizard.getCharacter().setConstitution(12);
        wizard.setConcentration(ConcentrationInstance.start("Haste", 3, 1, 10));

        // DC max(10, 7) = 10; a roll of 8 with CON +1 is 9, a failure
        f.dice.script(8);
        damage(wizard, 14, null);

        assertNull(wizard.getConcentration(), "Concentration should be lost after a failed check");
        assertEquals(9, f.entries(CombatActionType.CONCENTRATION_CHECK).getFirst().getRollTotal());
    }

    @Test
    @DisplayName("The DM's damage can't reach the dead: a 409")
    void damageToTheDead() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        CombatFixture.dead(goblin);

        ConflictException ex = assertThrows(ConflictException.class, () -> damage(goblin, 3, null));

        assertEquals("Goblin is already dead", ex.getMessage());
    }

    @Test
    @DisplayName("Damage and healing by hand are the DM's: a player gets 403 even for their own character (D028)")
    void damageAndHealingAreDmOnly() {
        EncounterParticipant rogue = f.player("Rogue", 20, 14);
        UUID playerUserId = f.playerControlling(rogue);
        DamageRequest damage = new DamageRequest();
        damage.setTargetId(rogue.getId());
        damage.setAmount(5);
        HealRequest heal = new HealRequest();
        heal.setTargetId(rogue.getId());
        heal.setAmount(5);

        assertThrows(ForbiddenException.class,
                () -> f.hitPoints.applyDamage(f.encounterId(), damage, rogue.getId(), playerUserId));
        assertThrows(ForbiddenException.class,
                () -> f.hitPoints.applyHealing(f.encounterId(), heal, rogue.getId(), playerUserId));
        assertEquals(20, rogue.getHpCurrent());
    }

    // ── Healing (10.7 to 10.9) ───────────────────────────────────

    @Test
    @DisplayName("10.7 Healing is capped at max HP")
    void healingCappedAtMaxHp() {
        EncounterParticipant fighter = f.player("Fighter", 45, 18);
        fighter.setHpCurrent(20);

        heal(fighter, 30);

        assertEquals(45, fighter.getHpCurrent());
        assertEquals(List.of("DM heals Fighter for 25 HP"), f.descriptions());
    }

    @Test
    @DisplayName("10.8 Healing a Dying PC wakes it with its death saves reset, still prone")
    void healingRevivesDyingPc() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        CombatFixture.downed(fighter, LifeState.DYING, 1);
        fighter.setDeathSaveSuccesses(2);

        heal(fighter, 5);

        assertEquals(5, fighter.getHpCurrent());
        assertEquals(LifeState.ALIVE, fighter.getLifeState());
        assertEquals(0, fighter.getDeathSaveSuccesses());
        assertEquals(0, fighter.getDeathSaveFailures());
        assertEquals(List.of("prone"), CombatFixture.conditionNames(fighter));
        assertEquals(1, f.entries(CombatActionType.REVIVE).size());
    }

    @Test
    @DisplayName("10.9 Healing doesn't revive a dead PC; Set HP does, logged as HP_SET (D161)")
    void deadPcIsRevivedOnlyBySetHp() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        CombatFixture.dead(fighter);
        fighter.setDeathSaveFailures(3);

        ConflictException refused = assertThrows(ConflictException.class, () -> heal(fighter, 10));
        setHp(fighter, 10, null);

        assertEquals("Fighter is dead, and healing can't bring them back; use Set HP to revive them",
                refused.getMessage());
        assertEquals(10, fighter.getHpCurrent());
        assertEquals(LifeState.ALIVE, fighter.getLifeState());
        assertEquals(0, fighter.getDeathSaveFailures());
        assertEquals(List.of("DM sets Fighter's HP to 10: Fighter is revived"),
                f.entries(CombatActionType.HP_SET).stream().map(e -> e.getDescription()).toList());
    }

    // ── Set HP / revive ──────────────────────────────────────────

    @Test
    @DisplayName("Set HP keeps HP within 0 and the maximum and sets temporary HP exactly")
    void setHpCorrections() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setHpTemp(8);

        setHp(fighter, 99, 3);

        assertEquals(30, fighter.getHpCurrent());
        assertEquals(3, fighter.getHpTemp());
        assertEquals("DM sets Fighter's HP to 30 and temporary HP to 3",
                f.entries(CombatActionType.HP_SET).getFirst().getDescription());
    }

    @Test
    @DisplayName("Set HP to 0 drops a PC as damage would, without massive damage, and kills a monster")
    void setHpToZero() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setConcentration(ConcentrationInstance.start("Bless", 1, 1, 10));
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        setHp(fighter, -5, null);
        setHp(goblin, 0, null);

        assertEquals(LifeState.DYING, fighter.getLifeState());
        assertEquals(List.of("unconscious", "prone"), CombatFixture.conditionNames(fighter));
        assertNull(fighter.getConcentration());
        assertEquals(LifeState.DEAD, goblin.getLifeState());
    }

    @Test
    @DisplayName("Set HP above 0 wakes a Stable PC and ends the unconsciousness of dropping to 0")
    void setHpWakesTheDowned() {
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        CombatFixture.downed(fighter, LifeState.STABLE, 0);

        setHp(fighter, 4, null);

        assertEquals(LifeState.ALIVE, fighter.getLifeState());
        assertEquals(List.of("prone"), CombatFixture.conditionNames(fighter));
    }

    @Test
    @DisplayName("Only the DM can Set HP")
    void setHpIsDmOnly() {
        EncounterParticipant rogue = f.player("Rogue", 20, 14);
        UUID playerUserId = f.playerControlling(rogue);
        SetHpRequest request = new SetHpRequest();
        request.setTargetId(rogue.getId());
        request.setHpCurrent(1);

        assertThrows(ForbiddenException.class, () -> f.hitPoints.setHp(f.encounterId(), request, playerUserId));
    }

    // ── Death saves (10.10 to 10.13) ─────────────────────────────

    @Test
    @DisplayName("10.10 Death save: 10 or more is a success")
    void deathSaveSuccess() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 0);

        deathSave(pc, 14);

        assertEquals(1, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.11 Death save: under 10 is a failure")
    void deathSaveFailure() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 0);

        deathSave(pc, 7);

        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(1, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.12 Death save: a natural 20 brings the PC back with 1 HP and ends its unconsciousness")
    void deathSaveNat20Revives() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 2);
        pc.setDeathSaveSuccesses(1);

        deathSave(pc, 20);

        assertEquals(1, pc.getHpCurrent());
        assertEquals(LifeState.ALIVE, pc.getLifeState());
        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
        assertEquals(List.of("prone"), CombatFixture.conditionNames(pc));
    }

    @Test
    @DisplayName("10.13 Death save: a natural 1 counts as two failures, here the third")
    void deathSaveNat1DoubleFailure() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 1);

        deathSave(pc, 1);

        assertEquals(3, pc.getDeathSaveFailures());
        assertEquals(LifeState.DEAD, pc.getLifeState());
    }

    @Test
    @DisplayName("The third success makes the PC Stable with both counts reset")
    void thirdSuccessStabilises() {
        EncounterParticipant pc = f.player("Fighter", 30, 18);
        CombatFixture.downed(pc, LifeState.DYING, 2);
        pc.setDeathSaveSuccesses(2);

        deathSave(pc, 12);

        assertEquals(LifeState.STABLE, pc.getLifeState());
        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("Only the Dying make death saves: Stable, Alive and Dead PCs get 409, monsters 400")
    void onlyTheDyingMakeDeathSaves() {
        EncounterParticipant stable = f.player("Stable", 30, 18);
        CombatFixture.downed(stable, LifeState.STABLE, 0);
        EncounterParticipant alive = f.player("Alive", 30, 18);
        EncounterParticipant dead = f.player("Dead", 30, 18);
        CombatFixture.dead(dead);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        assertEquals("Stable is stable and makes no death saves",
                assertThrows(ConflictException.class, () -> deathSave(stable, 10)).getMessage());
        assertEquals("Alive is not dying", assertThrows(ConflictException.class, () -> deathSave(alive, 10)).getMessage());
        assertThrows(ConflictException.class, () -> deathSave(dead, 10));
        assertThrows(ValidationException.class, () -> deathSave(goblin, 10));
    }

    // ── Concentration on the DM's damage (10.22 to 10.25) ────────

    @Test
    @DisplayName("10.22 The concentration DC is 10 or half the damage: a roll making 10 keeps it after 20 damage")
    void concentrationCheckDc() {
        EncounterParticipant wizard = f.player("Wizard", 50, 12);
        wizard.getCharacter().setConstitution(12);
        ConcentrationInstance haste = ConcentrationInstance.start("Haste", 3, 1, 10);
        wizard.setConcentration(haste);

        f.dice.script(9);
        damage(wizard, 20, null);

        assertEquals(haste, wizard.getConcentration(), "Concentration should be maintained");
    }

    @Test
    @DisplayName("10.23 The concentration check adds the CON save proficiency bonus")
    void concentrationCheckWithProficiency() {
        EncounterParticipant sorcerer = f.player("Sorcerer", 50, 12);
        sorcerer.getCharacter().setConstitution(14);
        sorcerer.getCharacter().setProficiencyBonus(3);
        sorcerer.getCharacter().setSavingThrowProficiencies(List.of("CON", "CHA"));
        ConcentrationInstance holdPerson = ConcentrationInstance.start("Hold Person", 2, 1, 10);
        sorcerer.setConcentration(holdPerson);

        // 5 + 2 (CON) + 3 (proficiency) = 10 against DC 10
        f.dice.script(5);
        damage(sorcerer, 10, null);

        assertEquals(holdPerson, sorcerer.getConcentration(), "Concentration maintained with proficiency bonus");
    }

    @Test
    @DisplayName("10.24 A failed concentration check ends the Concentration Instance and its conditions")
    void failedConcentrationCheckDropsSpell() {
        EncounterParticipant wizard = f.player("Wizard", 50, 12);
        ConcentrationInstance holdPerson = ConcentrationInstance.start("Hold Person", 2, 1, 10);
        wizard.setConcentration(holdPerson);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        goblin.setActiveConditions(new java.util.ArrayList<>(List.of(
                com.tabletophelper.encounter.state.ConditionEntry.fromEffect("paralyzed", 10, 1, "Hold Person",
                        wizard.getId(), holdPerson))));

        f.dice.script(8);
        damage(wizard, 10, null);

        assertNull(wizard.getConcentration(), "Concentration should be lost after a failed check");
        assertTrue(CombatFixture.conditionNames(goblin).isEmpty());
        assertTrue(f.descriptions().contains("paralyzed ends on Goblin (Hold Person concentration lost)"));
    }

    @Test
    @DisplayName("10.25 Dropping to 0 HP ends concentration without a save")
    void zeroHpAutoDropsConcentration() {
        EncounterParticipant wizard = f.player("Wizard", 30, 12);
        wizard.setHpCurrent(5);
        wizard.setConcentration(ConcentrationInstance.start("Haste", 3, 1, 10));

        damage(wizard, 10, null);

        assertEquals(0, wizard.getHpCurrent());
        assertEquals(LifeState.DYING, wizard.getLifeState());
        assertNull(wizard.getConcentration(), "Concentration ends at 0 HP");
    }

    // ── Lifecycle and locking (D160, D166) ───────────────────────

    @Test
    @DisplayName("Damage in a Paused encounter is a 409 that changes nothing; the DM resumes it first (D166)")
    void damageInPausedEncounterIsRefused() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        f.encounter.setStatus(EncounterStatus.PAUSED);

        ConflictException ex = assertThrows(ConflictException.class, () -> damage(goblin, 3, null));

        assertEquals("The encounter is paused until the DM resumes it", ex.getMessage());
        assertEquals(7, goblin.getHpCurrent());
        verify(f.combatLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("A user outside the campaign is refused before the encounter's row is locked")
    void nonMemberLocksNothing() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        DamageRequest request = new DamageRequest();
        request.setTargetId(goblin.getId());
        request.setAmount(3);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.hitPoints.applyDamage(f.encounterId(), request, null, UUID.randomUUID()));

        assertEquals("Only the DM can perform this action", ex.getMessage());
        verify(f.encounterRepository, never()).lockById(any());
        verifyNoInteractions(f.eventPublisher);
    }

    @Test
    @DisplayName("A combat command locks the encounter and announces the change, which is broadcast once it commits")
    void combatCommandLocksAndAnnounces() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        damage(goblin, 3, null);

        InOrder inOrder = inOrder(f.encounterRepository, f.eventPublisher);
        inOrder.verify(f.encounterRepository).limitLockWaits();
        inOrder.verify(f.encounterRepository).lockById(f.encounterId());
        inOrder.verify(f.eventPublisher).publishEvent(new EncounterChanged(f.encounterId()));
        verify(f.encounterRepository, never()).findById(any());
    }
}
