package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.ParticipantType;
import com.tabletophelper.encounter.dto.DamageRequest;
import com.tabletophelper.encounter.dto.UndoInfo;
import com.tabletophelper.encounter.state.CommandSnapshot;
import com.tabletophelper.encounter.state.ParticipantCombatState;
import com.tabletophelper.shared.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Recording combat commands for undo (M14): each command's row, its entries stamped with it, and before commit only
 * what it changed kept, or the row dropped when it changed nothing.
 */
class CommandRecorderTest {

    private CombatFixture f;
    private EncounterParticipant goblin;
    private EncounterParticipant orc;

    @BeforeEach
    void setUp() {
        f = new CombatFixture();
        f.recordCommands();
        goblin = f.monster("Goblin", 20, 15);
        orc = f.monster("Orc", 30, 13);
        goblin.setInitiative(15);
        orc.setInitiative(10);
        f.started();
    }

    private void damage(EncounterParticipant target, int amount) {
        DamageRequest request = new DamageRequest();
        request.setTargetId(target.getId());
        request.setAmount(amount);
        f.inTransaction(() -> f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId));
    }

    private CombatCommand lastCommand() {
        return f.commands.getLast();
    }

    @Test
    @DisplayName("A command is numbered after the last, stamps its entries, and keeps only the participants it changed")
    void keepsOnlyWhatChanged() {
        damage(orc, 7);

        CombatCommand command = lastCommand();
        assertEquals(2, command.getSequence());
        assertFalse(command.isBarrier());
        assertEquals(f.dmUserId, command.getIssuedBy());
        assertEquals("DAMAGE", command.getKind());
        assertEquals(f.log.getFirst().getDescription(), command.getSummary());
        assertTrue(f.log.stream().allMatch(entry -> command.getId().equals(entry.getCommandId())));

        CommandSnapshot before = command.getBeforeState();
        assertEquals(1, before.participants().size());
        ParticipantCombatState orcBefore = before.participants().getFirst();
        assertEquals(orc.getId(), orcBefore.id());
        assertEquals(30, orcBefore.hpCurrent());
        assertNull(before.encounter(), "the turn didn't move");
        assertTrue(before.logPatches().isEmpty());
    }

    @Test
    @DisplayName("A command that moves the turn keeps the encounter's turn as it was")
    void keepsTheTurn() {
        f.turnOf(goblin);

        f.inTransaction(() -> f.turns.advanceTurn(f.encounterId(), f.dmUserId));

        CommandSnapshot before = lastCommand().getBeforeState();
        assertEquals("TURN_ADVANCE", lastCommand().getKind());
        assertNotNull(before.encounter());
        assertEquals(goblin.getId(), before.encounter().currentTurnParticipantId());
        assertEquals(1, before.encounter().roundNumber());
    }

    @Test
    @DisplayName("A command that changes nothing and writes nothing leaves no row once it commits")
    void dropsAnEmptyCommand() {
        f.inTransaction(() -> f.turns.setLairStatus(f.encounterId(), List.of(), f.dmUserId));

        assertEquals(1, f.commands.size(), "only the start's barrier");
    }

    @Test
    @DisplayName("A command that writes no entry is named by its service, such as a lair status change")
    void namesACommandWithoutEntries() {
        f.inTransaction(() -> f.turns.setLairStatus(f.encounterId(), List.of(goblin.getId()), f.dmUserId));

        assertEquals("LAIR_STATUS", lastCommand().getKind());
        assertEquals("The DM changes which monsters fight in their lair", lastCommand().getSummary());
        assertNotNull(lastCommand().getBeforeState().encounter());
    }

    @Test
    @DisplayName("A command that rolls back leaves nothing behind, and outside a transaction nothing is recorded")
    void rollbackAndNoTransaction() {
        DamageRequest unknown = new DamageRequest();
        unknown.setTargetId(UUID.randomUUID());
        unknown.setAmount(3);
        assertThrows(RuntimeException.class, () ->
                f.inTransaction(() -> f.hitPoints.applyDamage(f.encounterId(), unknown, null, f.dmUserId)));

        DamageRequest request = new DamageRequest();
        request.setTargetId(goblin.getId());
        request.setAmount(3);
        f.hitPoints.applyDamage(f.encounterId(), request, null, f.dmUserId);

        assertEquals(1, f.commands.size());
        assertNull(f.log.getFirst().getCommandId());
        assertTrue(CommandRecorder.current().isEmpty());
    }

    @Test
    @DisplayName("Of the entries a command writes, the likeliest type and the one naming no target sum it up")
    void headline() {
        f.inTransaction(() -> {
            f.access.beginAsDm(f.encounterId(), f.dmUserId);
            f.combatLog.write(f.encounter, CombatActionType.SPELL_SLOT_USE, "Goblin uses a level 1 slot", null, goblin);
            f.combatLog.write(f.encounter, CombatActionType.SPELL_CAST, "Goblin casts Magic Missile at Orc", goblin, orc);
            f.combatLog.write(f.encounter, CombatActionType.SPELL_CAST, "Goblin casts Magic Missile", goblin, null);
            return null;
        });

        assertEquals("SPELL_CAST", lastCommand().getKind());
        assertEquals("Goblin casts Magic Missile", lastCommand().getSummary());
    }

    @Test
    @DisplayName("Legendary Resistance notes the entry it resolves as it was, so undo can leave it unresolved")
    void notesTheEntryLegendaryResistanceChanges() {
        orc.setResourcePoolsCurrent(CombatFixture.legendaryResistance(3));
        orc.setHpCurrent(25);
        CombatLog entry = CombatLog.builder().id(UUID.randomUUID()).encounter(f.encounter).roundNumber(1)
                .actionType(CombatActionType.MONSTER_ACTION).description("Goblin blasts Orc").targetId(orc.getId())
                .legendaryResistanceEligible(true).lrTargetId(orc.getId()).lrDamageDealt(5).lrResolved(false)
                .lrSuccessDamage(2).lrConditionIds(List.of()).build();
        when(f.combatLogRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        f.inTransaction(() -> f.legendaryResistance.useLegendaryResistance(f.encounterId(), entry.getId(), f.dmUserId));

        assertTrue(entry.getLrResolved());
        assertEquals(1, lastCommand().getBeforeState().logPatches().size());
        assertEquals(entry.getId(), lastCommand().getBeforeState().logPatches().getFirst().logId());
        assertFalse(lastCommand().getBeforeState().logPatches().getFirst().lrResolved());
    }

    @Test
    @DisplayName("The DM View's undo names the newest command, how many it wrote, and how many may be undone")
    void undoInfo() {
        damage(goblin, 3);
        damage(orc, 4);

        UndoInfo info = f.commandRecorder.undoInfo(f.encounter).orElseThrow();

        assertEquals(lastCommand().getId(), info.commandId());
        assertEquals("DAMAGE", info.kind());
        assertEquals(1, info.entryCount());
        assertEquals(2, info.stepsAvailable());
        assertEquals(2, info.recent().size());
        assertEquals(f.commands.get(1).getId(), info.recent().get(1).commandId());
    }

    @Test
    @DisplayName("Nothing before a barrier can be undone, and an encounter that isn't Active offers no undo")
    void barrierAndStatus() {
        damage(goblin, 3);
        f.commandRecorder.barrier(f.encounter, f.dmUserId, "PARTICIPANT_REMOVED", "Orc leaves the encounter");

        assertTrue(f.commandRecorder.undoInfo(f.encounter).isEmpty());
        assertThrows(ConflictException.class, () -> f.inTransaction(() -> f.undoService.undo(f.encounterId(),
                new com.tabletophelper.encounter.dto.UndoRequest(f.commands.get(1).getId(), null), f.dmUserId)));

        damage(goblin, 2);
        f.encounter.setStatus(com.tabletophelper.encounter.EncounterStatus.PAUSED);
        assertTrue(f.commandRecorder.undoInfo(f.encounter).isEmpty());
    }

    @Test
    @DisplayName("A command that brings a participant into the fight records it as created, and isn't empty (D182)")
    void recordsCreatedParticipants() {
        UUID created = UUID.randomUUID();
        f.inTransaction(() -> {
            f.commandRecorder.record(f.encounter, f.dmUserId);
            f.encounter.getParticipants().add(EncounterParticipant.builder().id(created).encounter(f.encounter)
                    .participantType(ParticipantType.COMPANION).displayName("Wolf").hpMax(11).hpCurrent(11)
                    .armourClass(13).build());
            return null;
        });

        CommandSnapshot before = lastCommand().getBeforeState();
        assertEquals(List.of(created), before.createdParticipantIds());
        assertTrue(before.participants().isEmpty());
        assertFalse(before.isEmpty());
    }

    @Test
    @DisplayName("A snapshot without created participants writes none, so snapshots from before V34 read back as written")
    void noCreatedParticipants_leftOut() {
        damage(orc, 7);
        assertNull(lastCommand().getBeforeState().createdParticipantIds());
        assertFalse(f.objectMapper.writeValueAsString(lastCommand().getBeforeState()).contains("createdParticipantIds"));
    }
}
