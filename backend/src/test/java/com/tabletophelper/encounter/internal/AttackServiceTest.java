package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.AttackRollRequest;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import com.tabletophelper.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Weapon attacks (D161). From the old CombatServiceTest: the attack rolls 10.14 to 10.21 and the on-turn
 * access cases. A natural 20 or a forced critical doubles the dice; the dice come from the Rng, where the old
 * test mocked ThreadLocalRandom and DiceRoller.
 */
class AttackServiceTest {

    private CombatFixture f;
    private EncounterParticipant rogue;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        rogue = f.player("Rogue", 30, 14);
    }

    private static AttackRollRequest request(EncounterParticipant target, int bonus, String dice) {
        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(target.getId());
        request.setAttackBonus(bonus);
        request.setDamageDice(dice);
        return request;
    }

    private void attack(AttackRollRequest request, int... rolls) {
        f.dice.script(rolls);
        f.attacks.rollAttack(f.encounterId(), request, rogue.getId(), f.dmUserId);
    }

    @Test
    @DisplayName("10.14 A normal hit deals the damage, with its type")
    void normalHit() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);
        AttackRollRequest request = request(goblin, 5, "1d8+3");
        request.setDamageType("slashing");

        // 12 + 5 = 17 against AC 15, then 5 on the d8
        attack(request, 12, 5);

        assertEquals(12, goblin.getHpCurrent());
        CombatLog entry = f.entries(CombatActionType.ATTACK).getFirst();
        assertEquals(12, entry.getRollValue());
        assertEquals(17, entry.getRollTotal());
        assertEquals(8, entry.getDamageDealt());
        assertEquals("Rogue attacks Goblin: d20(12) + 5 = 17 vs AC 15 — hit, 8 slashing damage.", entry.getDescription());
    }

    @Test
    @DisplayName("10.15 A miss does no damage")
    void miss() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 18);

        attack(request(goblin, 5, "1d8+3"), 8);

        assertEquals(20, goblin.getHpCurrent());
        assertEquals("Rogue attacks Goblin: d20(8) + 5 = 13 vs AC 18 — miss.", f.descriptions().getFirst());
    }

    @Test
    @DisplayName("10.16 A natural 20 hits whatever the AC, with critical damage")
    void naturalTwentyAlwaysHits() {
        EncounterParticipant goblin = f.monster("Goblin", 30, 25);

        // The d8 twice: 4 + 6 + 3 = 13
        attack(request(goblin, 2, "1d8+3"), 20, 4, 6);

        assertEquals(17, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("10.17 A natural 1 misses whatever the total")
    void naturalOneAlwaysMisses() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 10);

        attack(request(goblin, 15, "1d8+3"), 1);

        assertEquals(20, goblin.getHpCurrent());
        assertTrue(f.descriptions().getFirst().endsWith("natural 1, miss."));
    }

    @Test
    @DisplayName("10.18 Advantage uses the higher of two d20 rolls")
    void advantage() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);
        AttackRollRequest request = request(goblin, 3, "1d8+3");
        request.setAdvantage(true);

        attack(request, 8, 15, 5);

        assertEquals(12, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("10.19 Disadvantage uses the lower of two d20 rolls")
    void disadvantage() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);
        AttackRollRequest request = request(goblin, 3, "1d8+3");
        request.setAdvantage(false);

        attack(request, 8, 15);

        assertEquals(20, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("10.20 The DM's forced critical hits as a critical hit whatever the roll, logged as a Manual Modifier")
    void forcedCritical() {
        EncounterParticipant goblin = f.monster("Goblin", 30, 15);
        AttackRollRequest request = request(goblin, 5, "1d8+3");
        request.setForceCrit(true);

        attack(request, 5, 6, 6);

        assertEquals(15, goblin.getHpCurrent());
        assertEquals("Manual Modifier (DM): Rogue's attack is a critical hit.", f.descriptions().getFirst());
        assertTrue(f.descriptions().get(1).contains("critical hit (forced)"));
    }

    @Test
    @DisplayName("10.21 Separate attacks apply their damage independently")
    void separateAttacks() {
        EncounterParticipant goblin = f.monster("Goblin", 30, 15);

        attack(request(goblin, 5, "1d8+3"), 15, 5);
        assertEquals(22, goblin.getHpCurrent());
        attack(request(goblin, 5, "1d8+3"), 12, 3);

        assertEquals(16, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("An attack's damage meets the target's defences, which weapon attacks skipped before (D161)")
    void weaponDamageMeetsDefences() {
        EncounterParticipant skeleton = f.monster("Skeleton", 20, 10);
        skeleton.getMonster().setDamageVulnerabilities("[\"bludgeoning\"]");
        AttackRollRequest request = request(skeleton, 5, "1d6+2");
        request.setDamageType("Bludgeoning");

        attack(request, 15, 4);

        assertEquals(8, skeleton.getHpCurrent());
    }

    @Test
    @DisplayName("Without a within5Ft value the attacker counts as within 5 feet: a hit on an unconscious PC is critical")
    void withinFiveFeetByDefault() {
        EncounterParticipant fighter = f.player("Fighter", 30, 12);
        CombatFixture.downed(fighter, LifeState.DYING, 0);
        AttackRollRequest request = request(fighter, 5, "4");
        request.setWithin5Ft(null);

        attack(request, 3, 14);

        assertEquals(2, fighter.getDeathSaveFailures());
    }

    @Test
    @DisplayName("A player's forced critical is a Manual Modifier: used and logged while the campaign allows it (D164)")
    void playerForcesACritical() {
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 30, 15);
        rogue.setSortOrder(0);
        AttackRollRequest request = request(goblin, 5, "1d6");
        request.setForceCrit(true);

        f.dice.script(3, 4, 4);
        f.attacks.rollAttack(f.encounterId(), request, rogue.getId(), playerUserId);

        assertEquals(22, goblin.getHpCurrent());
        assertEquals("Manual Modifier (player): Rogue's attack is a critical hit.", f.descriptions().getFirst());
    }

    @Test
    @DisplayName("A player's forced critical is a 403 when the campaign doesn't allow Manual Modifiers; nothing rolls")
    void playerCannotForceACriticalWhenDisallowed() {
        UUID playerUserId = f.playerControlling(rogue);
        f.campaign.setAllowPlayerManualModifiers(false);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        AttackRollRequest request = request(goblin, 5, "1d6");
        request.setForceCrit(true);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.attacks.rollAttack(f.encounterId(), request, rogue.getId(), playerUserId));

        assertEquals("This campaign doesn't allow players to enter Manual Modifiers", ex.getMessage());
        assertEquals(7, goblin.getHpCurrent());
        assertTrue(f.log.isEmpty());
    }

    @Test
    @DisplayName("The dead can't be attacked: a 409 (D161)")
    void deadTargetIsRefused() {
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        CombatFixture.dead(goblin);

        ConflictException ex = assertThrows(ConflictException.class, () -> attack(request(goblin, 5, "1d6"), 15));

        assertEquals("Goblin is dead", ex.getMessage());
    }

    @Test
    @DisplayName("A player who names a hidden monster gets the 400 of an unknown one (D157)")
    void playerCannotTargetAHiddenMonster() {
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant bugbear = f.monster("Bugbear", 27, 16);
        bugbear.setIsVisibleToPlayers(false);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> f.attacks.rollAttack(f.encounterId(), request(bugbear, 5, "1d6"), rogue.getId(), playerUserId));

        assertEquals("Participant not found", ex.getMessage());
    }

    @Test
    @DisplayName("A player attacking when it is not their turn gets 403 (D028)")
    void playerAttackOutOfTurnIsForbidden() {
        EncounterParticipant fighter = f.player("Fighter", 30, 16);
        f.turnOf(fighter);
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(), playerUserId));

        assertEquals("You can only attack on your turn", ex.getMessage());
        assertEquals(7, goblin.getHpCurrent());
        verify(f.combatLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("A player may attack on their own turn (D028)")
    void playerAttackOnTurnIsAllowed() {
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant goblin = f.monster("Goblin", 7, 15);
        f.dice.script(15, 3);

        assertDoesNotThrow(() -> f.attacks.rollAttack(f.encounterId(), request(goblin, 5, "1d6"), rogue.getId(),
                playerUserId));
        assertEquals(4, goblin.getHpCurrent());
    }
}
