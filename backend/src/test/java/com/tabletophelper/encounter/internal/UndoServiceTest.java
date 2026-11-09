package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UndoRequest;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DM's undo (M14): the newest commands' before-states put back, newest first, the commands marked undone with an
 * UNDO entry each, and the refusals: a stale expectedCommandId, nothing to undo, a barrier, a player, a paused
 * encounter.
 */
class UndoServiceTest {

    private CombatFixture f;
    private EncounterParticipant goblin;
    private EncounterParticipant fighter;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        fighter = f.player("Fighter", 30, 16);
        goblin = f.monster("Goblin", 7, 15);
        fighter.setInitiative(18);
        goblin.setInitiative(12);
        f.started();
    }

    private void damage(EncounterParticipant target, int amount) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        f.inTransaction(() -> f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId));
    }

    private void undo(int steps) {
        undo(f.commands.getLast().getId(), steps);
    }

    private void undo(UUID expected, Integer steps) {
        f.inTransaction(() -> f.undoService.undo(f.encounterId(), new UndoRequest(expected, steps), f.dmUserId));
    }

    @Test
    @DisplayName("Undoing a death brings the monster back with its hit points, and the command is marked undone")
    void undoesADeath() {
        damage(goblin, 10);
        assertEquals(LifeState.DEAD, goblin.getLifeState());
        CombatCommand killed = f.commands.getLast();

        undo(1);

        assertEquals(LifeState.ALIVE, goblin.getLifeState());
        assertEquals(7, goblin.getHpCurrent());
        assertNotNull(killed.getUndoneAt());
        assertEquals(f.dmUserId, killed.getUndoneBy());
        CombatLog undoEntry = f.entries(CombatActionType.UNDO).getFirst();
        assertEquals("The DM undoes: " + killed.getSummary(), undoEntry.getDescription());
        assertEquals(killed.getId(), undoEntry.getUndoesCommandId());
        assertNull(undoEntry.getCommandId(), "undo is not itself recorded, so there is no redo");
        assertEquals(2, f.commands.size(), "no command recorded for the undo");
    }

    @Test
    @DisplayName("Several commands undo newest first, back to the state before the oldest")
    void multiStep() {
        damage(fighter, 5);
        ConditionRequest prone = new ConditionRequest();
        prone.setTargetId(fighter.getId());
        prone.setCondition("prone");
        f.inTransaction(() -> f.conditionService.addCondition(f.encounterId(), prone, f.dmUserId));
        damage(fighter, 6);
        assertEquals(19, fighter.getHpCurrent());

        undo(3);

        assertEquals(30, fighter.getHpCurrent());
        assertTrue(CombatFixture.conditionNames(fighter).isEmpty());
        assertEquals(3, f.entries(CombatActionType.UNDO).size());
        assertTrue(f.commandRecorder.undoInfo(f.encounter).isEmpty());
    }

    @Test
    @DisplayName("Undoing Next Turn gives back the turn, the round and the budget the new turn refreshed")
    void undoesNextTurn() {
        f.turnOf(goblin);
        TurnEconomy spent = new TurnEconomy(1, 1, true, 0, true, true, false, false, true, false, false, false);
        fighter.setTurnEconomy(spent);

        f.inTransaction(() -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));
        assertEquals(2, f.encounter.getRoundNumber());
        assertEquals(TurnEconomy.fresh(), fighter.getTurnEconomy());

        undo(1);

        assertEquals(goblin.getId(), f.encounter.getCurrentTurnParticipantId());
        assertEquals(1, f.encounter.getRoundNumber());
        assertEquals(spent, fighter.getTurnEconomy());
    }

    @Test
    @DisplayName("Undo restores Spell Effects (M15): undoing the concentration loss brings Moonbeam's effect back, "
            + "undoing the cast takes it away")
    void undoesSpellEffects() {
        f.spell("Moonbeam", 2, """
                {"spellLevel": 2, "deliveryMethod": "SAVING_THROW", "saveAbility": "CON", "halfOnSave": true,
                 "concentration": true, "durationRounds": 10, "repeatEffect": true, "repeatActionType": "ACTION",
                 "directedResolves": false, "effectTriggers": [{"when": ["ENTER", "START_OF_TURN"]}],
                 "effects": [{"effectType": "DAMAGE", "damageDice": "2d10", "damageType": "radiant"}]}""");
        f.knows(fighter, "Moonbeam");
        fighter.setSpellSlotsCurrent(Map.of("2", new SpellSlot(2, 0)));
        CastSpellRequest cast = new CastSpellRequest();
        cast.setSpellName("Moonbeam");
        cast.setSlotLevel(2);
        cast.setTargetIds(List.of());
        f.dice.script(1, 1);
        f.inTransaction(() -> f.spellcasting.castSpell(f.encounterId(), cast, fighter.getId(), f.dmUserId));
        SpellEffectEntry moonbeam = fighter.getActiveEffects().getFirst();
        damage(fighter, 30);
        assertTrue(fighter.getActiveEffects().isEmpty());
        assertNull(fighter.getConcentration());

        undo(1);

        assertEquals(List.of(moonbeam), fighter.getActiveEffects());
        assertEquals(moonbeam.concentrationInstanceId(), fighter.getConcentration().id());

        undo(f.commands.get(f.commands.size() - 2).getId(), 1);

        assertTrue(fighter.getActiveEffects().isEmpty());
        assertNull(fighter.getConcentration());
        assertEquals(2, fighter.getSpellSlotsCurrent().get("2").remaining());
    }

    @Test
    @DisplayName("A snapshot taken before V33, with no activeEffects, leaves the participant's effects as they are")
    void snapshotBeforeSpellEffects() {
        List<SpellEffectEntry> effects = List.of(SpellEffectEntry.start("Spiritual Weapon", 2, null, 1, 10, null));
        fighter.setActiveEffects(effects);
        ParticipantCombatState old = new ParticipantCombatState(fighter.getId(), 30, 0, LifeState.ALIVE, 0, 0, false,
                null, null, null, null, null, TurnEconomy.fresh(), null);

        old.restore(fighter);

        assertEquals(effects, fighter.getActiveEffects());
    }

    @Test
    @DisplayName("Undo is refused when the newest command isn't the one the DM reviewed, or there is none")
    void refusals() {
        ConflictException nothing = assertThrows(ConflictException.class, () -> undo(UUID.randomUUID(), null));
        assertEquals("Nothing to undo", nothing.getMessage());

        damage(goblin, 2);
        UUID reviewed = f.commands.getLast().getId();
        damage(goblin, 1);

        ConflictException stale = assertThrows(ConflictException.class, () -> undo(reviewed, null));
        assertEquals(CommandRecorder.STALE, stale.getMessage());
        ConflictException tooMany = assertThrows(ConflictException.class, () -> undo(5));
        assertEquals("Only 2 actions can be undone", tooMany.getMessage());
        assertEquals(4, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("Only the DM may undo, and only while the encounter is Active")
    void dmOnlyAndActive() {
        damage(goblin, 2);
        UUID player = f.playerControlling(fighter);

        assertThrows(ForbiddenException.class, () -> f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                new UndoRequest(f.commands.getLast().getId(), null), player)));

        f.encounter.setStatus(EncounterStatus.PAUSED);
        assertThrows(ConflictException.class, () -> undo(1));
        assertEquals(5, goblin.getHpCurrent());
    }

    @Test
    @DisplayName("Legendary Resistance can't be used on an entry whose action was undone")
    void legendaryResistanceRefusesUndoneEntries() {
        goblin.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
        damage(goblin, 2);
        CombatLog entry = f.log.stream().filter(e -> e.getActionType() == CombatActionType.DAMAGE).findFirst()
                .orElseThrow();
        entry.setLegendaryResistanceEligible(true);
        entry.setLrTargetId(goblin.getId());
        entry.setLrResolved(false);
        entry.setLrSuccessDamage(1);
        entry.setLrConditionIds(List.of());
        org.mockito.Mockito.when(f.combatLogRepository.findById(entry.getId()))
                .thenReturn(java.util.Optional.of(entry));
        undo(1);

        ConflictException refused = assertThrows(ConflictException.class, () -> f.inTransaction(() ->
                f.legendaryResistance.useLegendaryResistance(f.encounterId(), entry.getId(), f.dmUserId)));
        assertTrue(refused.getMessage().contains("undone"));
    }
}
