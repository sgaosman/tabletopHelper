package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommandRepository;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Legendary Resistance, used on a combat log entry that recorded a failed save (D145, D163): the creature
 * succeeds instead, taking the save's success outcome, and spends a use.
 */
@Service
@RequiredArgsConstructor
public class LegendaryResistanceService {

    private final CombatAccess access;
    private final EffectApplier effectApplier;
    private final ResourcePoolService resourcePoolService;
    private final CombatLogRepository combatLogRepository;
    private final CombatCommandRepository combatCommandRepository;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public EncounterResponse useLegendaryResistance(UUID encounterId, UUID combatLogId, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);

        // An entry from another encounter counts as missing (D155)
        CombatLog entry = combatLogRepository.findById(combatLogId)
                .filter(found -> found.getEncounter().getId().equals(encounterId))
                .orElseThrow(() -> new NotFoundException("Combat log entry not found"));
        // The DM undid the action that wrote it, so the save it records never happened (M14)
        if (entry.getCommandId() != null && combatCommandRepository.existsByIdAndUndoneAtIsNotNull(entry.getCommandId())) {
            throw new ConflictException("That action was undone, so Legendary Resistance can't be used on it");
        }
        if (Boolean.TRUE.equals(entry.getLrResolved())) {
            throw new ConflictException("Legendary Resistance already resolved for this entry");
        }
        if (!Boolean.TRUE.equals(entry.getLegendaryResistanceEligible())) {
            throw new ConflictException("This combat log entry is not eligible for Legendary Resistance");
        }
        if (entry.getLrSuccessDamage() == null) {
            throw new ConflictException("This entry was logged before Legendary Resistance could undo a save "
                    + "exactly, so it can't be used on it");
        }

        EncounterParticipant target = CombatAccess.participant(encounter, entry.getLrTargetId());
        if (!resourcePoolService.spendPool(target, EffectApplier.LEGENDARY_RESISTANCE_POOL, 1)) {
            throw new ConflictException("No legendary resistance remaining");
        }
        EffectApplier.LegendaryResistanceUndo undo = effectApplier.undoFailedSave(target, entry);
        // Undoing this command leaves the entry unresolved again (M14)
        CommandRecorder.current().ifPresent(recording -> recording.patched(entry));
        entry.setLrResolved(true);
        combatLogRepository.save(entry);

        combatLog.write(encounter, CombatActionType.LEGENDARY_RESISTANCE_USED,
                describe(target, undo, EffectApplier.legendaryResistanceRemaining(target)), target, target);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private static String describe(EncounterParticipant target, EffectApplier.LegendaryResistanceUndo undo,
                                   int remaining) {
        List<String> changes = new ArrayList<>();
        if (undo.regained() > 0) {
            changes.add((undo.revived() ? "is back with " : "regains ") + undo.regained() + " HP");
        }
        if (!undo.conditionsRemoved().isEmpty()) {
            changes.add("loses " + String.join(", ", undo.conditionsRemoved()));
        }
        return target.getDisplayName() + " uses Legendary Resistance and succeeds on the save instead"
                + (changes.isEmpty() ? "" : ": " + String.join(" and ", changes))
                + " (" + remaining + " remaining)";
    }
}
