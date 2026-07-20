package com.tabletophelper.encounter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.encounter.dto.*;
import com.tabletophelper.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CombatServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private CombatLogRepository combatLogRepository;
    @Mock private EncounterService encounterService;
    @Mock private SpellResolverEngine spellResolverEngine;

    private CombatService combatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID dmUserId;
    private UUID encounterId;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        combatService = new CombatService(encounterRepository, combatLogRepository, encounterService, objectMapper, spellResolverEngine);

        dmUserId = UUID.randomUUID();
        encounterId = UUID.randomUUID();

        User dmUser = User.builder()
                .id(dmUserId)
                .username("dm")
                .email("dm@test.com")
                .passwordHash("hash")
                .build();

        Campaign campaign = Campaign.builder()
                .id(UUID.randomUUID())
                .name("Test Campaign")
                .dm(dmUser)
                .inviteCode("ABC123")
                .build();

        encounter = Encounter.builder()
                .id(encounterId)
                .campaign(campaign)
                .name("Test Encounter")
                .status(EncounterStatus.ACTIVE)
                .currentTurnIndex(0)
                .roundNumber(1)
                .participants(new ArrayList<>())
                .build();

        lenient().when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        lenient().when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(encounterService.toResponse(any(Encounter.class))).thenReturn(EncounterResponse.builder().build());
        lenient().when(combatLogRepository.save(any(CombatLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- Helper methods ----

    private EncounterParticipant addMonster(String name, int hp, int ac) {
        EncounterParticipant p = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.MONSTER)
                .displayName(name)
                .hpMax(hp)
                .hpCurrent(hp)
                .hpTemp(0)
                .armourClass(ac)
                .isAlive(true)
                .isCurrentTurn(false)
                .deathSaveSuccesses(0)
                .deathSaveFailures(0)
                .sortOrder(encounter.getParticipants().size())
                .build();
        encounter.getParticipants().add(p);
        return p;
    }

    private EncounterParticipant addPlayer(String name, int hp, int ac) {
        EncounterParticipant p = EncounterParticipant.builder()
                .id(UUID.randomUUID())
                .encounter(encounter)
                .participantType(ParticipantType.PLAYER)
                .displayName(name)
                .hpMax(hp)
                .hpCurrent(hp)
                .hpTemp(0)
                .armourClass(ac)
                .isAlive(true)
                .isCurrentTurn(false)
                .deathSaveSuccesses(0)
                .deathSaveFailures(0)
                .controlledByUserId(dmUserId)
                .sortOrder(encounter.getParticipants().size())
                .build();
        encounter.getParticipants().add(p);
        return p;
    }

    // ================================================================
    // Damage flow (10.1-10.6)
    // ================================================================

    @Test
    @DisplayName("10.1 Normal damage reduces HP")
    void normalDamageReducesHp() {
        EncounterParticipant goblin = addMonster("Goblin", 7, 15);

        DamageRequest request = new DamageRequest();
        request.setTargetId(goblin.getId());
        request.setAmount(3);
        request.setDamageType("slashing");

        combatService.applyDamage(encounterId, request, null, dmUserId);

        assertEquals(4, goblin.getHpCurrent());
        assertTrue(goblin.getIsAlive());
    }

    @Test
    @DisplayName("10.2 Temp HP absorbs damage before real HP")
    void tempHpAbsorbsDamage() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setHpTemp(10);

        DamageRequest request = new DamageRequest();
        request.setTargetId(fighter.getId());
        request.setAmount(15);

        combatService.applyDamage(encounterId, request, null, dmUserId);

        assertEquals(0, fighter.getHpTemp());
        assertEquals(25, fighter.getHpCurrent());
        assertTrue(fighter.getIsAlive());
    }

    @Test
    @DisplayName("10.3 Monster at 0 HP is killed; PC at 0 HP enters dying state")
    void damageToZeroHpMonsterKilledPcDying() {
        EncounterParticipant goblin = addMonster("Goblin", 3, 15);
        EncounterParticipant pc = addPlayer("Fighter", 3, 18);

        DamageRequest monsterDmg = new DamageRequest();
        monsterDmg.setTargetId(goblin.getId());
        monsterDmg.setAmount(5);
        combatService.applyDamage(encounterId, monsterDmg, null, dmUserId);

        DamageRequest pcDmg = new DamageRequest();
        pcDmg.setTargetId(pc.getId());
        pcDmg.setAmount(5);
        combatService.applyDamage(encounterId, pcDmg, null, dmUserId);

        assertEquals(0, goblin.getHpCurrent());
        assertFalse(goblin.getIsAlive());

        assertEquals(0, pc.getHpCurrent());
        assertFalse(pc.getIsAlive());
        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.4 Massive damage to downed PC exceeding max HP causes instant death")
    void massiveDamageInstantDeath() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveFailures(0);

        DamageRequest request = new DamageRequest();
        request.setTargetId(pc.getId());
        request.setAmount(30); // equals maxHp -> instant death

        combatService.applyDamage(encounterId, request, null, dmUserId);

        assertEquals(3, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.5 Damage to dying PC adds a death save failure")
    void damageToDyingPcAddsFailure() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveFailures(1);

        DamageRequest request = new DamageRequest();
        request.setTargetId(pc.getId());
        request.setAmount(5);

        combatService.applyDamage(encounterId, request, null, dmUserId);

        assertEquals(2, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.6 Damage to concentrating creature triggers concentration check")
    void damageTriggerConcentrationCheck() {
        EncounterParticipant wizard = addPlayer("Wizard", 30, 12);
        PlayerCharacter pc = PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .constitution(12) // +1 mod
                .proficiencyBonus(2)
                .build();
        wizard.setCharacter(pc);
        wizard.setConcentrationSpell("Haste");

        DamageRequest request = new DamageRequest();
        request.setTargetId(wizard.getId());
        request.setAmount(14); // DC = max(10, 7) = 10

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            // Roll 8 + CON mod 1 = 9 < DC 10 -> fail
            when(mockRandom.nextInt(1, 21)).thenReturn(8);

            combatService.applyDamage(encounterId, request, null, dmUserId);
        }

        assertNull(wizard.getConcentrationSpell(), "Concentration should be lost after failed check");
    }

    // ================================================================
    // Healing flow (10.7-10.9)
    // ================================================================

    @Test
    @DisplayName("10.7 Healing is capped at max HP")
    void healingCappedAtMaxHp() {
        EncounterParticipant fighter = addPlayer("Fighter", 45, 18);
        fighter.setHpCurrent(20);

        HealRequest request = new HealRequest();
        request.setTargetId(fighter.getId());
        request.setAmount(30);

        combatService.applyHealing(encounterId, request, null, dmUserId);

        assertEquals(45, fighter.getHpCurrent());
    }

    @Test
    @DisplayName("10.8 Healing a dying PC revives them with death saves reset")
    void healingRevivesDyingPc() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setHpCurrent(0);
        fighter.setIsAlive(false);
        fighter.setDeathSaveSuccesses(2);
        fighter.setDeathSaveFailures(1);

        HealRequest request = new HealRequest();
        request.setTargetId(fighter.getId());
        request.setAmount(5);

        combatService.applyHealing(encounterId, request, null, dmUserId);

        assertEquals(5, fighter.getHpCurrent());
        assertTrue(fighter.getIsAlive());
        assertEquals(0, fighter.getDeathSaveSuccesses());
        assertEquals(0, fighter.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.9 Healing a dead PC revives with Prone condition")
    void healingDeadPcRevivesWithProne() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setHpCurrent(0);
        fighter.setIsAlive(false);
        fighter.setDeathSaveFailures(3);

        HealRequest request = new HealRequest();
        request.setTargetId(fighter.getId());
        request.setAmount(10);

        combatService.applyHealing(encounterId, request, null, dmUserId);

        assertEquals(10, fighter.getHpCurrent());
        assertTrue(fighter.getIsAlive());
        assertEquals(0, fighter.getDeathSaveSuccesses());
        assertEquals(0, fighter.getDeathSaveFailures());
        assertNotNull(fighter.getActiveConditions());
        assertTrue(fighter.getActiveConditions().contains("prone"));
    }

    // ================================================================
    // Death saves (10.10-10.13)
    // ================================================================

    @Test
    @DisplayName("10.10 Death save: roll 10+ is a success")
    void deathSaveSuccess() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveSuccesses(0);
        pc.setDeathSaveFailures(0);

        DeathSaveRequest request = new DeathSaveRequest();
        request.setParticipantId(pc.getId());

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(14);

            combatService.rollDeathSave(encounterId, request, dmUserId);
        }

        assertEquals(1, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.11 Death save: roll < 10 is a failure")
    void deathSaveFailure() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveSuccesses(0);
        pc.setDeathSaveFailures(0);

        DeathSaveRequest request = new DeathSaveRequest();
        request.setParticipantId(pc.getId());

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(7);

            combatService.rollDeathSave(encounterId, request, dmUserId);
        }

        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(1, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.12 Death save: natural 20 revives with 1 HP")
    void deathSaveNat20Revives() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveSuccesses(1);
        pc.setDeathSaveFailures(2);

        DeathSaveRequest request = new DeathSaveRequest();
        request.setParticipantId(pc.getId());

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(20);

            combatService.rollDeathSave(encounterId, request, dmUserId);
        }

        assertEquals(1, pc.getHpCurrent());
        assertTrue(pc.getIsAlive());
        assertEquals(0, pc.getDeathSaveSuccesses());
        assertEquals(0, pc.getDeathSaveFailures());
    }

    @Test
    @DisplayName("10.13 Death save: natural 1 counts as two failures")
    void deathSaveNat1DoubleFailure() {
        EncounterParticipant pc = addPlayer("Fighter", 30, 18);
        pc.setHpCurrent(0);
        pc.setIsAlive(false);
        pc.setDeathSaveSuccesses(0);
        pc.setDeathSaveFailures(1);

        DeathSaveRequest request = new DeathSaveRequest();
        request.setParticipantId(pc.getId());

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(1);

            combatService.rollDeathSave(encounterId, request, dmUserId);
        }

        assertEquals(3, pc.getDeathSaveFailures()); // 1 + 2 = 3 -> dead
    }

    // ================================================================
    // Attack rolls (10.14-10.21)
    // ================================================================

    @Test
    @DisplayName("10.14 Attack roll: normal hit deals damage")
    void attackRollNormalHit() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(5);
        request.setDamageDice("1d8+3");
        request.setDamageType("slashing");

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(12, 10);
            // Normal: uses roll1=12, total=12+5=17 >= AC 15 -> hit

            diceMock.when(() -> DiceRoller.roll("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(5, 3, 8, 1, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(12, goblin.getHpCurrent()); // 20 - 8 = 12
    }

    @Test
    @DisplayName("10.15 Attack roll: miss does no damage")
    void attackRollMiss() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 18);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(5);
        request.setDamageDice("1d8+3");

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(8, 5);
            // Normal: roll1=8, total=8+5=13 < AC 18 -> miss

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(20, goblin.getHpCurrent()); // unchanged
    }

    @Test
    @DisplayName("10.16 Natural 20 always hits with critical damage regardless of AC")
    void attackRollNat20AlwaysHits() {
        EncounterParticipant goblin = addMonster("Goblin", 30, 25);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(2);
        request.setDamageDice("1d8+3");

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(20, 5);
            // Nat 20: total=22 < AC 25 but auto-hits

            diceMock.when(() -> DiceRoller.rollCritical("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(10, 3, 13, 2, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(17, goblin.getHpCurrent()); // 30 - 13 = 17
    }

    @Test
    @DisplayName("10.17 Natural 1 always misses regardless of total")
    void attackRollNat1AlwaysMisses() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 10);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(15); // total 16, well above AC 10
        request.setDamageDice("1d8+3");

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(1, 5);
            // Nat 1: auto-miss regardless

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(20, goblin.getHpCurrent()); // unchanged
    }

    @Test
    @DisplayName("10.18 Advantage uses the higher of two d20 rolls")
    void attackRollAdvantage() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(3);
        request.setDamageDice("1d8+3");
        request.setAdvantage(true);

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(8, 15);
            // Advantage: max(8,15)=15, total=15+3=18 >= AC 15 -> hit

            diceMock.when(() -> DiceRoller.roll("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(5, 3, 8, 1, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(12, goblin.getHpCurrent()); // 20 - 8 = 12
    }

    @Test
    @DisplayName("10.19 Disadvantage uses the lower of two d20 rolls")
    void attackRollDisadvantage() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(3);
        request.setDamageDice("1d8+3");
        request.setAdvantage(false); // false = disadvantage

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(8, 15);
            // Disadvantage: min(8,15)=8, total=8+3=11 < AC 15 -> miss

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(20, goblin.getHpCurrent()); // unchanged
    }

    @Test
    @DisplayName("10.20 Force crit flag treats attack as critical hit")
    void attackRollForceCrit() {
        EncounterParticipant goblin = addMonster("Goblin", 30, 15);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(5);
        request.setDamageDice("1d8+3");
        request.setForceCrit(true);

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(5, 3);
            // forceCrit: always hits, uses rollCritical for damage

            diceMock.when(() -> DiceRoller.rollCritical("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(12, 3, 15, 2, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }

        assertEquals(15, goblin.getHpCurrent()); // 30 - 15 = 15
    }

    @Test
    @DisplayName("10.21 Multiple attack calls apply damage independently")
    void multipleAttacksIndependent() {
        EncounterParticipant goblin = addMonster("Goblin", 30, 15);

        AttackRollRequest request = new AttackRollRequest();
        request.setTargetId(goblin.getId());
        request.setAttackBonus(5);
        request.setDamageDice("1d8+3");

        // First attack: hit for 8 damage
        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(15, 10);

            diceMock.when(() -> DiceRoller.roll("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(5, 3, 8, 1, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }
        assertEquals(22, goblin.getHpCurrent()); // 30 - 8 = 22

        // Second attack: hit for 6 damage
        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class);
             MockedStatic<DiceRoller> diceMock = mockStatic(DiceRoller.class)) {

            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            when(mockRandom.nextInt(1, 21)).thenReturn(12, 8);

            diceMock.when(() -> DiceRoller.roll("1d8+3"))
                    .thenReturn(new DiceRoller.RollResult(3, 3, 6, 1, 8));

            combatService.rollAttack(encounterId, request, null, dmUserId);
        }
        assertEquals(16, goblin.getHpCurrent()); // 22 - 6 = 16
    }

    // ================================================================
    // Concentration (10.22-10.25)
    // ================================================================

    @Test
    @DisplayName("10.22 Concentration DC is max(10, damage/2)")
    void concentrationCheckDC() {
        EncounterParticipant wizard = addPlayer("Wizard", 50, 12);
        PlayerCharacter pc = PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .constitution(12) // +1 mod
                .proficiencyBonus(2)
                .build();
        wizard.setCharacter(pc);
        wizard.setConcentrationSpell("Haste");

        DamageRequest request = new DamageRequest();
        request.setTargetId(wizard.getId());
        request.setAmount(20); // DC = max(10, 10) = 10

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            // Roll 9 + CON mod 1 = 10 >= DC 10 -> pass
            when(mockRandom.nextInt(1, 21)).thenReturn(9);

            combatService.applyDamage(encounterId, request, null, dmUserId);
        }

        assertEquals("Haste", wizard.getConcentrationSpell(), "Concentration should be maintained");
    }

    @Test
    @DisplayName("10.23 Concentration check includes CON save proficiency bonus")
    void concentrationCheckWithProficiency() {
        EncounterParticipant sorcerer = addPlayer("Sorcerer", 50, 12);
        PlayerCharacter pc = PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .constitution(14) // +2 mod
                .proficiencyBonus(3)
                .savingThrowProficiencies("[\"CON\",\"CHA\"]")
                .build();
        sorcerer.setCharacter(pc);
        sorcerer.setConcentrationSpell("Hold Person");

        DamageRequest request = new DamageRequest();
        request.setTargetId(sorcerer.getId());
        request.setAmount(10); // DC = max(10, 5) = 10

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            // Roll 5 + 2 (CON) + 3 (prof) = 10 >= DC 10 -> pass
            when(mockRandom.nextInt(1, 21)).thenReturn(5);

            combatService.applyDamage(encounterId, request, null, dmUserId);
        }

        assertEquals("Hold Person", sorcerer.getConcentrationSpell(),
                "Concentration maintained with proficiency bonus");
    }

    @Test
    @DisplayName("10.24 Failed concentration check drops concentration")
    void failedConcentrationCheckDropsSpell() {
        EncounterParticipant wizard = addPlayer("Wizard", 50, 12);
        PlayerCharacter pc = PlayerCharacter.builder()
                .id(UUID.randomUUID())
                .constitution(10) // +0 mod
                .proficiencyBonus(2)
                .build();
        wizard.setCharacter(pc);
        wizard.setConcentrationSpell("Hold Person");

        DamageRequest request = new DamageRequest();
        request.setTargetId(wizard.getId());
        request.setAmount(10); // DC = max(10, 5) = 10

        try (MockedStatic<ThreadLocalRandom> tlrMock = mockStatic(ThreadLocalRandom.class)) {
            ThreadLocalRandom mockRandom = mock(ThreadLocalRandom.class);
            tlrMock.when(ThreadLocalRandom::current).thenReturn(mockRandom);
            // Roll 8 + 0 (CON) = 8 < DC 10 -> fail
            when(mockRandom.nextInt(1, 21)).thenReturn(8);

            combatService.applyDamage(encounterId, request, null, dmUserId);
        }

        assertNull(wizard.getConcentrationSpell(), "Concentration should be lost after failed check");
    }

    @Test
    @DisplayName("10.25 Dropping to 0 HP auto-drops concentration without a save")
    void zeroHpAutoDropsConcentration() {
        EncounterParticipant wizard = addPlayer("Wizard", 30, 12);
        wizard.setHpCurrent(5);
        wizard.setConcentrationSpell("Haste");

        DamageRequest request = new DamageRequest();
        request.setTargetId(wizard.getId());
        request.setAmount(10); // drops from 5 to 0

        combatService.applyDamage(encounterId, request, null, dmUserId);

        assertEquals(0, wizard.getHpCurrent());
        assertFalse(wizard.getIsAlive());
        assertNull(wizard.getConcentrationSpell(), "Concentration auto-dropped at 0 HP");
    }

    // ================================================================
    // Conditions (10.26-10.29)
    // ================================================================

    @Test
    @DisplayName("10.26 Add condition with duration")
    void addConditionWithDuration() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);

        ConditionRequest request = new ConditionRequest();
        request.setTargetId(goblin.getId());
        request.setCondition("Frightened");
        request.setDuration(3);

        combatService.addCondition(encounterId, request, dmUserId);

        assertNotNull(goblin.getActiveConditions());
        assertTrue(goblin.getActiveConditions().contains("frightened"));
    }

    @Test
    @DisplayName("10.27 Condition auto-expires after its duration in rounds")
    void conditionAutoExpires() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setSortOrder(0);
        fighter.setIsCurrentTurn(true);

        EncounterParticipant goblin = addMonster("Goblin", 20, 15);
        goblin.setSortOrder(1);
        // Set Blinded condition: duration=2, applied in round 1
        goblin.setActiveConditions("[{\"name\":\"blinded\",\"duration\":2,\"appliedRound\":1}]");

        // Set encounter to round 3 so condition expires (3-1=2 >= duration 2)
        encounter.setRoundNumber(3);

        combatService.advanceTurn(encounterId, dmUserId);

        // After advancing to Goblin's turn in round 3, Blinded should expire
        String conditions = goblin.getActiveConditions();
        assertTrue(conditions == null || !conditions.contains("blinded"),
                "Blinded should have expired after 2 rounds");
    }

    @Test
    @DisplayName("10.28 Manual condition removal removes only the specified condition")
    void removeConditionManually() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);
        goblin.setActiveConditions(
                "[{\"name\":\"restrained\",\"duration\":null,\"appliedRound\":1},"
                        + "{\"name\":\"poisoned\",\"duration\":null,\"appliedRound\":1}]");

        ConditionRequest request = new ConditionRequest();
        request.setTargetId(goblin.getId());
        request.setCondition("Restrained");

        combatService.removeCondition(encounterId, request, dmUserId);

        assertNotNull(goblin.getActiveConditions());
        assertFalse(goblin.getActiveConditions().contains("restrained"));
        assertTrue(goblin.getActiveConditions().contains("poisoned"));
    }

    @Test
    @DisplayName("10.29 Multiple conditions can exist simultaneously")
    void multipleConditions() {
        EncounterParticipant goblin = addMonster("Goblin", 20, 15);

        ConditionRequest req1 = new ConditionRequest();
        req1.setTargetId(goblin.getId());
        req1.setCondition("Poisoned");
        combatService.addCondition(encounterId, req1, dmUserId);

        ConditionRequest req2 = new ConditionRequest();
        req2.setTargetId(goblin.getId());
        req2.setCondition("Prone");
        combatService.addCondition(encounterId, req2, dmUserId);

        assertNotNull(goblin.getActiveConditions());
        assertTrue(goblin.getActiveConditions().contains("poisoned"));
        assertTrue(goblin.getActiveConditions().contains("prone"));
    }

    // ================================================================
    // Turn management (10.30-10.32)
    // ================================================================

    @Test
    @DisplayName("10.30 Advance turn moves to next participant in initiative order")
    void advanceTurnNextParticipant() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setSortOrder(0);
        fighter.setIsCurrentTurn(true);

        EncounterParticipant wizard = addPlayer("Wizard", 20, 12);
        wizard.setSortOrder(1);

        EncounterParticipant goblin = addMonster("Goblin", 7, 15);
        goblin.setSortOrder(2);

        combatService.advanceTurn(encounterId, dmUserId);

        assertFalse(fighter.getIsCurrentTurn());
        assertTrue(wizard.getIsCurrentTurn());
        assertEquals(1, encounter.getCurrentTurnIndex());
        assertEquals(1, encounter.getRoundNumber()); // no round change
    }

    @Test
    @DisplayName("10.31 Advance turn at end of round wraps and increments round counter")
    void advanceTurnWrapsRound() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setSortOrder(0);

        EncounterParticipant goblin = addMonster("Goblin", 7, 15);
        goblin.setSortOrder(1);
        goblin.setIsCurrentTurn(true);

        encounter.setCurrentTurnIndex(1);

        combatService.advanceTurn(encounterId, dmUserId);

        assertTrue(fighter.getIsCurrentTurn());
        assertFalse(goblin.getIsCurrentTurn());
        assertEquals(0, encounter.getCurrentTurnIndex());
        assertEquals(2, encounter.getRoundNumber()); // round incremented
    }

    @Test
    @DisplayName("10.32 Previous turn moves back to the previous participant")
    void previousTurnMovesBack() {
        EncounterParticipant fighter = addPlayer("Fighter", 30, 18);
        fighter.setSortOrder(0);

        EncounterParticipant wizard = addPlayer("Wizard", 20, 12);
        wizard.setSortOrder(1);
        wizard.setIsCurrentTurn(true);

        encounter.setCurrentTurnIndex(1);

        combatService.previousTurn(encounterId, dmUserId);

        assertTrue(fighter.getIsCurrentTurn());
        assertFalse(wizard.getIsCurrentTurn());
        assertEquals(0, encounter.getCurrentTurnIndex());
    }
}
