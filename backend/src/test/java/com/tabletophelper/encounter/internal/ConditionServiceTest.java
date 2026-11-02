package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.EndRule;
import com.tabletophelper.shared.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conditions by hand (D028, D162). From the old CombatServiceTest: 10.26, 10.28 and 10.29, and a player
 * changing another player's conditions; 10.27, a condition expiring, went to TurnServiceTest.
 */
class ConditionServiceTest {

    private CombatFixture f;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
    }

    private static ConditionRequest request(EncounterParticipant target, String condition, Integer duration) {
        ConditionRequest request = new ConditionRequest();
        request.setTargetId(target.getId());
        request.setCondition(condition);
        request.setDuration(duration);
        return request;
    }

    @Test
    @DisplayName("10.26 A condition added with a duration is stored in lower case, and ends by hand or after its rounds")
    void addConditionWithDuration() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);

        f.conditionService.addCondition(f.encounterId(), request(goblin, "Frightened", 3), f.dmUserId);

        ConditionEntry entry = goblin.getActiveConditions().getFirst();
        assertEquals("frightened", entry.name());
        assertEquals(3, entry.duration());
        assertEquals(1, entry.appliedRound());
        // Its rounds count on its own creature's turns (D162)
        assertEquals(EndRule.afterRounds(null), entry.endRule());
        assertNotNull(entry.id());
        assertNull(entry.concentrationInstanceId());
        assertEquals(List.of("Goblin gains condition: frightened (3 rounds)"), f.descriptions());
    }

    @Test
    @DisplayName("10.28 Removing a condition by hand removes only that condition, and is logged")
    void removeConditionManually() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);
        goblin.setActiveConditions(new ArrayList<>(List.of(
                ConditionEntry.manual("restrained", null, 1), ConditionEntry.manual("poisoned", null, 1))));

        f.conditionService.removeCondition(f.encounterId(), request(goblin, "Restrained", null), f.dmUserId);

        assertEquals(List.of("poisoned"), CombatFixture.conditionNames(goblin));
        assertEquals(List.of("Goblin loses condition: restrained"), f.descriptions());
    }

    @Test
    @DisplayName("10.29 Several conditions can be held at once, and adding one already held does nothing")
    void multipleConditions() {
        EncounterParticipant goblin = f.monster("Goblin", 20, 15);

        f.conditionService.addCondition(f.encounterId(), request(goblin, "Poisoned", null), f.dmUserId);
        f.conditionService.addCondition(f.encounterId(), request(goblin, "Prone", null), f.dmUserId);
        f.conditionService.addCondition(f.encounterId(), request(goblin, "prone", null), f.dmUserId);

        assertEquals(List.of("poisoned", "prone"), CombatFixture.conditionNames(goblin));
        assertEquals(2, f.entries(CombatActionType.CONDITION_ADD).size());
    }

    @Test
    @DisplayName("An incapacitating condition added by hand ends the creature's concentration and its conditions (D162)")
    void incapacitatingConditionEndsConcentration() {
        EncounterParticipant cleric = f.player("Cleric", 30, 16);
        ConcentrationInstance bless = ConcentrationInstance.start("Bless", 1, 1, 10);
        cleric.setConcentration(bless);
        EncounterParticipant fighter = f.player("Fighter", 30, 18);
        fighter.setActiveConditions(new ArrayList<>(List.of(
                ConditionEntry.fromEffect("blessed", 10, 1, "Bless", cleric.getId(), bless))));

        f.conditionService.addCondition(f.encounterId(), request(cleric, "Unconscious", null), f.dmUserId);

        assertNull(cleric.getConcentration());
        assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
    }

    @Test
    @DisplayName("A player may add and remove conditions on their own character, and each removal is logged (D028)")
    void playerManagesOwnConditions() {
        EncounterParticipant rogue = f.player("Rogue", 20, 14);
        UUID playerUserId = f.playerControlling(rogue);

        f.conditionService.addCondition(f.encounterId(), request(rogue, "prone", null), playerUserId);
        f.conditionService.removeCondition(f.encounterId(), request(rogue, "prone", null), playerUserId);

        assertNull(rogue.getActiveConditions());
        assertEquals(List.of("Rogue gains condition: prone", "Rogue loses condition: prone"), f.descriptions());
    }

    @Test
    @DisplayName("A player cannot add a condition to another player's character")
    void playerCannotChangeAnotherPlayersConditions() {
        EncounterParticipant rogue = f.player("Rogue", 20, 14);
        UUID playerUserId = f.playerControlling(rogue);
        EncounterParticipant cleric = f.player("Cleric", 20, 14);
        f.playerControlling(cleric);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> f.conditionService.addCondition(f.encounterId(), request(cleric, "prone", null), playerUserId));

        assertEquals("You do not have permission to perform this action", ex.getMessage());
        assertNull(cleric.getActiveConditions());
    }
}
