package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Legendary Resistance on a logged failed save (D145, D163): the creature takes the save's success outcome
 * instead. From the old CombatServiceTest, the six LR cases, changed by D163 where they refunded all the
 * damage or removed conditions by name: reverting damage, removing the save's conditions, spending the
 * target's use, a second use, an ineligible entry and an entry from another encounter. Entries logged before
 * R5 can't be undone.
 */
class LegendaryResistanceServiceTest {

    private CombatFixture f;
    private EncounterParticipant dragon;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        dragon = f.monster("Adult Red Dragon", 200, 19);
        dragon.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
    }

    /** A failed save's entry as the EffectApplier writes it. */
    private CombatLog failedSave(EncounterParticipant target, int lost, int successDamage, List<UUID> conditionIds) {
        CombatLog entry = CombatLog.builder()
                .id(UUID.randomUUID())
                .encounter(f.encounter)
                .roundNumber(1)
                .actorName("Aboleth")
                .targetId(target.getId())
                .targetName(target.getDisplayName())
                .actionType(CombatActionType.MONSTER_ACTION)
                .description("Aboleth uses Psychic Blast on " + target.getDisplayName())
                .damageDealt(lost)
                .legendaryResistanceEligible(true)
                .lrTargetId(target.getId())
                .lrDamageDealt(lost)
                .lrResolved(false)
                .lrSuccessDamage(successDamage)
                .lrConditionIds(conditionIds)
                .build();
        when(f.combatLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        return entry;
    }

    private void use(CombatLog entry) {
        f.legendaryResistance.useLegendaryResistance(f.encounterId(), entry.getId(), f.dmUserId);
    }

    @Test
    @DisplayName("It gives back only the damage beyond the success outcome: half of a save-for-half (D163)")
    void refundsOnlyTheDifference() {
        dragon.setHpCurrent(170);
        CombatLog entry = failedSave(dragon, 30, 15, List.of());

        use(entry);

        assertEquals(185, dragon.getHpCurrent());
        assertTrue(entry.getLrResolved());
        assertTrue(f.log.contains(entry));
        assertEquals("Adult Red Dragon uses Legendary Resistance and succeeds on the save instead: regains 15 HP "
                + "(2 remaining)", f.entries(CombatActionType.LEGENDARY_RESISTANCE_USED).getFirst().getDescription());
    }

    @Test
    @DisplayName("It removes exactly the conditions the save applied, by ID, and leaves another of the same name")
    void removesTheSavesConditions() {
        ConditionEntry fromTheSave = ConditionEntry.manual("charmed", null, 1);
        ConditionEntry fromElsewhere = ConditionEntry.manual("charmed", null, 1);
        dragon.setActiveConditions(new ArrayList<>(List.of(fromTheSave, fromElsewhere)));
        CombatLog entry = failedSave(dragon, 0, 0, List.of(fromTheSave.id()));

        use(entry);

        assertEquals(List.of(fromElsewhere), dragon.getActiveConditions());
    }

    @Test
    @DisplayName("A save that killed the monster brings it back with the hit points a success would have left")
    void bringsBackAMonsterTheSaveKilled() {
        EncounterParticipant ogre = f.monster("Ogre", 59, 11);
        ogre.setResourcePoolsCurrent(CombatFixture.legendaryResistance(1));
        // At 20 HP it failed against 28 damage; a success would have dealt 14
        CombatFixture.dead(ogre);
        CombatLog entry = failedSave(ogre, 20, 14, List.of());

        use(entry);

        assertEquals(LifeState.ALIVE, ogre.getLifeState());
        assertEquals(6, ogre.getHpCurrent());
        assertTrue(f.entries(CombatActionType.LEGENDARY_RESISTANCE_USED).getFirst().getDescription()
                .contains("is back with 6 HP"));
    }

    @Test
    @DisplayName("A monster a success would have killed too stays dead")
    void aMonsterThatWouldHaveDiedAnywayStaysDead() {
        EncounterParticipant ogre = f.monster("Ogre", 59, 11);
        ogre.setResourcePoolsCurrent(CombatFixture.legendaryResistance(1));
        CombatFixture.dead(ogre);
        CombatLog entry = failedSave(ogre, 10, 14, List.of());

        use(entry);

        assertEquals(LifeState.DEAD, ogre.getLifeState());
    }

    @Test
    @DisplayName("It spends the target's use, not the attacker's")
    void spendsTheTargetsUse() {
        EncounterParticipant aboleth = f.monster("Aboleth", 135, 17);
        aboleth.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
        CombatLog entry = failedSave(dragon, 20, 10, List.of());

        use(entry);

        assertEquals(2, EffectApplier.legendaryResistanceRemaining(dragon));
        assertEquals(3, EffectApplier.legendaryResistanceRemaining(aboleth));
    }

    @Test
    @DisplayName("A second use on the same entry is a 409")
    void secondUseIsRefused() {
        CombatLog entry = failedSave(dragon, 10, 5, List.of());
        entry.setLrResolved(true);

        assertThrows(ConflictException.class, () -> use(entry));
        assertEquals(3, EffectApplier.legendaryResistanceRemaining(dragon));
    }

    @Test
    @DisplayName("An entry that isn't a failed save is a 409")
    void ineligibleEntryIsRefused() {
        CombatLog entry = CombatLog.builder()
                .id(UUID.randomUUID()).encounter(f.encounter).roundNumber(1)
                .actionType(CombatActionType.ATTACK).description("Fighter attacks Goblin: hit")
                .legendaryResistanceEligible(false).lrResolved(false)
                .build();
        when(f.combatLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        assertThrows(ConflictException.class, () -> use(entry));
    }

    @Test
    @DisplayName("An entry logged before R5, without the success outcome, is a 409 that changes nothing")
    void entryFromBeforeR5IsRefused() {
        dragon.setHpCurrent(170);
        CombatLog entry = failedSave(dragon, 30, 15, List.of());
        entry.setLrSuccessDamage(null);
        entry.setLrConditionIds(null);

        ConflictException ex = assertThrows(ConflictException.class, () -> use(entry));

        assertTrue(ex.getMessage().contains("logged before"));
        assertEquals(170, dragon.getHpCurrent());
        assertEquals(3, EffectApplier.legendaryResistanceRemaining(dragon));
    }

    @Test
    @DisplayName("Without uses left it is a 409")
    void noUsesLeft() {
        dragon.setResourcePoolsCurrent(CombatFixture.legendaryResistance(0));
        dragon.setHpCurrent(170);
        CombatLog entry = failedSave(dragon, 30, 15, List.of());

        assertThrows(ConflictException.class, () -> use(entry));
        assertEquals(170, dragon.getHpCurrent());
    }

    @Test
    @DisplayName("An entry from another encounter is reported as not found, and nothing changes")
    void entryFromAnotherEncounterIsNotFound() {
        Encounter otherEncounter = Encounter.builder().id(UUID.randomUUID()).campaign(f.campaign).name("Other")
                .status(EncounterStatus.ACTIVE).participants(new ArrayList<>()).build();
        CombatLog entry = failedSave(dragon, 10, 5, List.of());
        entry.setEncounter(otherEncounter);

        NotFoundException ex = assertThrows(NotFoundException.class, () -> use(entry));

        assertEquals("Combat log entry not found", ex.getMessage());
        assertFalse(entry.getLrResolved());
        assertEquals(3, EffectApplier.legendaryResistanceRemaining(dragon));
    }
}
