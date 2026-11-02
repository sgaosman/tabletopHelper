package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.CastSpellRequest;
import com.tabletophelper.encounter.dto.CastSpellResponse;
import com.tabletophelper.encounter.dto.ConcentrationRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.RepeatSpellEffectRequest;
import com.tabletophelper.encounter.dto.SpellSlotRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A participant's spellcasting in combat: casting with the numbers the caster gives (until R5 workstream F
 * checks Spell Sources), using a lasting spell's effect again, spell slots, and concentration set by hand.
 * A player acts only for their own character, and casts only on its turn (D028).
 */
@Service
@RequiredArgsConstructor
public class SpellcastingService {

    private final CombatAccess access;
    private final SpellCaster spellCaster;
    private final SpellEffects spellEffects;
    private final EffectApplier effectApplier;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    @Transactional
    public CastSpellResponse castSpell(UUID encounterId, CastSpellRequest request, UUID actorParticipantId,
                                       UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        requireTargetCount(request);
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);

        int slotLevel = request.getSlotLevel();
        if (slotLevel > 0) {
            String slotKey = Boolean.TRUE.equals(request.getUsePactSlot()) ? "pact_" + slotLevel
                    : String.valueOf(slotLevel);
            Map<String, SpellSlot> slots = slotsOf(caster);
            SpellSlot slot = slots.get(slotKey);
            if (slot == null || slot.remaining() <= 0) {
                throw new ConflictException("No level " + slotLevel + " spell slots remaining");
            }
            SpellSlot spent = slot.spend();
            slots.put(slotKey, spent);
            caster.setSpellSlotsCurrent(slots);
            combatLog.write(encounter, CombatActionType.SPELL_SLOT_USE, caster.getDisplayName() + " uses a level "
                    + slotLevel + " spell slot (" + spent.remaining() + "/" + spent.max() + " remaining)", null, caster);
        }

        int attackBonus = request.getOverrideSpellAttackBonus() != null ? request.getOverrideSpellAttackBonus()
                : caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0;
        int saveDc = request.getOverrideSpellSaveDC() != null ? request.getOverrideSpellSaveDC()
                : caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10;
        SpellCaster.CastResult result = spellCaster.cast(encounter, caster, request.getSpellName(), slotLevel,
                targets, attackBonus, saveDc, request.getAdvantage());

        if (!result.concentration() && spellEffects.persistsWithoutConcentration(request.getSpellName())) {
            caster.setActiveSpell(request.getSpellName());
            caster.setActiveSpellSlotLevel(slotLevel);
        }
        return response(encounter, result, slotLevel);
    }

    /** Uses the caster's concentration spell's effect again, or else its lasting spell's. */
    @Transactional
    public CastSpellResponse repeatSpellEffect(UUID encounterId, RepeatSpellEffectRequest request,
                                               UUID actorParticipantId, UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);

        String spellName;
        int slotLevel;
        ConcentrationInstance concentration = caster.getConcentration();
        if (concentration != null) {
            spellName = concentration.spell();
            slotLevel = concentration.slotLevel() != null ? concentration.slotLevel() : 0;
        } else if (caster.getActiveSpell() != null) {
            spellName = caster.getActiveSpell();
            slotLevel = caster.getActiveSpellSlotLevel() != null ? caster.getActiveSpellSlotLevel() : 0;
        } else {
            throw new ConflictException("Caster has no active concentration or persistent spell");
        }
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);

        int attackBonus = request.getOverrideSpellAttackBonus() != null ? request.getOverrideSpellAttackBonus()
                : caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0;
        int saveDc = request.getOverrideSpellSaveDC() != null ? request.getOverrideSpellSaveDC()
                : caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10;
        SpellCaster.CastResult result = spellCaster.repeat(encounter, caster, spellName, slotLevel, targets,
                attackBonus, saveDc, request.getAdvantage());
        return response(encounter, result, slotLevel);
    }

    @Transactional
    public EncounterResponse useSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        Map<String, SpellSlot> slots = slotsOf(participant);
        String key = slotKey(slots, request.getSlotLevel());
        SpellSlot slot = slots.get(key);
        if (slot == null || slot.remaining() <= 0) {
            throw new ConflictException("No level " + request.getSlotLevel() + " spell slots remaining");
        }

        SpellSlot spent = slot.spend();
        slots.put(key, spent);
        participant.setSpellSlotsCurrent(slots);
        combatLog.write(encounter, CombatActionType.SPELL_SLOT_USE, participant.getDisplayName() + " uses a level "
                + key + " spell slot (" + spent.remaining() + "/" + spent.max() + " remaining)", null, participant);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    @Transactional
    public EncounterResponse restoreSpellSlot(UUID encounterId, SpellSlotRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        Map<String, SpellSlot> slots = slotsOf(participant);
        String key = slotKey(slots, request.getSlotLevel());
        SpellSlot slot = slots.get(key);
        if (slot == null) {
            throw new ValidationException("Participant has no level " + request.getSlotLevel() + " spell slots");
        }
        if (slot.remaining() >= slot.max()) {
            throw new ConflictException("Level " + key + " spell slots already at maximum");
        }

        SpellSlot restored = slot.restore();
        slots.put(key, restored);
        participant.setSpellSlotsCurrent(slots);
        combatLog.write(encounter, CombatActionType.SPELL_SLOT_RESTORE, participant.getDisplayName()
                + " restores a level " + key + " spell slot (" + restored.remaining() + "/" + restored.max()
                + " remaining)", null, participant);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    /**
     * Concentration set by hand (D028): naming a spell starts a Concentration Instance for it, ending the one
     * held first; naming none ends it. Either way, the conditions the ended instance created end too (D162).
     */
    @Transactional
    public EncounterResponse setConcentration(UUID encounterId, ConcentrationRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.getParticipantId(), userId);
        EncounterParticipant participant = CombatAccess.participant(encounter, request.getParticipantId());
        String spellName = request.getSpellName() != null && !request.getSpellName().isBlank()
                ? request.getSpellName().trim() : null;
        if (spellName != null) {
            effectApplier.startConcentration(encounter, participant, spellName, null, null);
        } else {
            effectApplier.endConcentration(encounter, participant, "ended by hand");
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private CastSpellResponse response(Encounter encounter, SpellCaster.CastResult result, int slotLevel) {
        return CastSpellResponse.builder()
                .encounterState(encounterService.toResponse(encounterRepository.save(encounter)))
                .spellName(result.spellName())
                .slotLevelUsed(slotLevel)
                .autoResolved(result.autoResolved())
                .resultSummary(result.summary())
                .targets(result.targets())
                .manualResolutionReason(result.manualReason())
                .build();
    }

    private void requireTargetCount(CastSpellRequest request) {
        OptionalInt max = spellEffects.maxTargets(request.getSpellName(), request.getSlotLevel());
        if (max.isPresent() && request.getTargetIds().size() > max.getAsInt()) {
            int limit = max.getAsInt();
            throw new ValidationException(request.getSpellName() + " can target at most " + limit
                    + " creature" + (limit != 1 ? "s" : "") + " at level " + request.getSlotLevel()
                    + ", but " + request.getTargetIds().size() + " targets were selected");
        }
    }

    // A level's slots, or a Warlock's pact slots of that level when there are none
    private static String slotKey(Map<String, SpellSlot> slots, int level) {
        String key = String.valueOf(level);
        return slots.containsKey(key) || !slots.containsKey("pact_" + level) ? key : "pact_" + level;
    }

    // A copy, so callers can change it and set it back
    private static Map<String, SpellSlot> slotsOf(EncounterParticipant participant) {
        return participant.getSpellSlotsCurrent() != null
                ? new LinkedHashMap<>(participant.getSpellSlotsCurrent()) : new LinkedHashMap<>();
    }
}
