package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.ConditionRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.ExhaustionRequest;
import com.tabletophelper.encounter.dto.RepeatSaveRequest;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.Exhaustion;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Conditions by hand: the DM on anyone, a player on their own character (D028). They end when removed, or as
 * their end rules say at turn boundaries, and every removal is logged (D162). A player the campaign prompts
 * rolls their character's Repeat Saves here.
 */
@Service
@RequiredArgsConstructor
public class ConditionService {

    private final CombatAccess access;
    private final ActionEconomyKeeper economy;
    private final EffectApplier effectApplier;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse addCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getTargetId(), userId);
        // Exhaustion has levels, not a plain entry (PHB p.291, D183): the DM sets it on its own
        String condition = request.getCondition() != null ? request.getCondition().trim() : "";
        if (condition.equalsIgnoreCase(Exhaustion.CONDITION) || condition.toLowerCase().startsWith("exhausted")) {
            throw new ValidationException("Exhaustion has six levels; the DM sets a creature's level with the "
                    + "exhaustion control");
        }
        effectApplier.addCondition(encounter, CombatAccess.participant(encounter, request.getTargetId()),
                request.getCondition(), request.getDuration());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse removeCondition(UUID encounterId, ConditionRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getTargetId(), userId);
        effectApplier.removeCondition(encounter, CombatAccess.participant(encounter, request.getTargetId()),
                request.getCondition());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * The DM sets a creature's exhaustion level, 0 to 6 (PHB p.291, D183), while the encounter is prepared or under
     * way, logged, and undoable once under way (D176). Players can't: a player character's level on its sheet is its
     * owner's to set between encounters, and Write-back takes the encounter's change there (D166).
     */
    @Transactional
    public EncounterResponse setExhaustion(UUID encounterId, ExhaustionRequest request, UUID userId) {
        Encounter encounter = access.beginAsDmPreparingOrActive(encounterId, userId);
        effectApplier.setExhaustion(encounter, CombatAccess.participant(encounter, request.participantId()),
                request.level());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * A Repeat Save left for the player to roll at the end of their character's turn (D162), by the player or
     * the DM. The server rolls it, logged as REPEAT_SAVE, and a success ends the condition. An unknown
     * condition is a 400; one with no roll waiting is a 409.
     */
    @Transactional
    public EncounterResponse rollRepeatSave(UUID encounterId, RepeatSaveRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        ConditionEntry condition = (participant.getActiveConditions() != null
                ? participant.getActiveConditions() : List.<ConditionEntry>of()).stream()
                .filter(c -> request.conditionId().equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Condition not found"));
        if (condition.repeatSave() == null || !condition.repeatSave().awaitingRoll()) {
            throw new ConflictException("No Repeat Save against " + condition.name() + " is waiting for a roll");
        }
        effectApplier.repeatSave(encounter, participant, condition.id());
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * A creature uses its action on the save against a condition that ends only that way, as Otto's Irresistible
     * Dance's Wisdom save (PHB p.264, D188): on its turn, charged as an action, by the DM or the creature's player. The
     * server rolls it, logged as REPEAT_SAVE; a success ends the condition and the spell with it, so the spell's Ongoing
     * Modifiers end too. An unknown condition is a 400; one without such a save is a 409.
     */
    @Transactional
    public EncounterResponse rollActionSave(UUID encounterId, RepeatSaveRequest request, UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, request.participantId(), userId,
                CombatAccess.OFF_TURN_ACTION);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        ConditionEntry condition = (participant.getActiveConditions() != null
                ? participant.getActiveConditions() : List.<ConditionEntry>of()).stream()
                .filter(c -> request.conditionId().equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Condition not found"));
        if (condition.repeatSave() == null || !condition.repeatSave().madeByAction()) {
            throw new ConflictException(condition.name() + " isn't ended by a save made with an action");
        }
        economy.charge(encounter, participant, Charge.of(ActionCost.ACTION,
                        EncounterTurns.isCurrentTurn(encounter, participant.getId())), userId,
                new ActionEconomyKeeper.Command(CombatActionType.REPEAT_SAVE,
                        participant.getDisplayName() + "'s save against " + condition.name()));
        effectApplier.repeatSave(encounter, participant, condition.id());
        boolean ended = participant.getActiveConditions() == null || participant.getActiveConditions().stream()
                .noneMatch(c -> c.id().equals(condition.id()));
        if (ended && condition.sourceParticipantId() != null && condition.concentrationInstanceId() != null) {
            encounter.getParticipants().stream()
                    .filter(p -> p.getId().equals(condition.sourceParticipantId()))
                    .filter(p -> p.getConcentration() != null
                            && p.getConcentration().id().equals(condition.concentrationInstanceId()))
                    .findFirst()
                    .ifPresent(caster -> effectApplier.endConcentration(encounter, caster, condition.sourceSpellName()
                            + " ends: " + participant.getDisplayName() + " succeeds on the save"));
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
