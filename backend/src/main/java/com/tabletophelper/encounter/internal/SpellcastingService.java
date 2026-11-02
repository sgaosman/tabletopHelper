package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
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
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.SpellSlotRequest;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.SpellSlot;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
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
 * A participant's spellcasting in combat: casting a spell paid for from a Spell Source, the same way for player
 * characters and monsters (D165); using a lasting spell's effect again; spell slots; and concentration set by
 * hand. A player acts only for their own character, casting only on its turn (D028) and only what the character
 * can cast now (D164). A spell attack bonus or save DC entered in place of the caster's is a Manual Modifier,
 * which a player may enter only while the campaign allows it.
 */
@Service
@RequiredArgsConstructor
public class SpellcastingService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final CasterSpells casterSpells;
    private final SpellCaster spellCaster;
    private final SpellEffects spellEffects;
    private final EffectApplier effectApplier;
    private final ManualModifiers manualModifiers;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Casts a spell for a player character or a monster: everything is checked before anything is spent, then
     * the Spell Source pays, and the spell is cast at the level that source gives.
     */
    @Transactional
    public CastSpellResponse castSpell(UUID encounterId, CastSpellRequest request, UUID actorParticipantId,
                                       UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(caster, request.getOverrideSpellAttackBonus(),
                request.getOverrideSpellSaveDC());
        manualModifiers.require(encounter, userId, modifiers);
        SpellSources.Payment payment = casterSpells.payment(caster, request.getSpellName(), request.getSpellSource(),
                request.getSlotLevel());
        requireTargetCount(payment, request.getTargetIds().size());
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);

        casterSpells.spend(encounter, caster, payment);
        manualModifiers.log(encounter, CombatActionType.SPELL_CAST, caster,
                caster.getDisplayName() + "'s " + payment.spellName(), userId, modifiers);
        SpellCaster.CastResult result = spellCaster.cast(encounter, caster, payment.spellName(), payment.castLevel(),
                targets, attackBonus(caster, request.getOverrideSpellAttackBonus()),
                saveDc(caster, request.getOverrideSpellSaveDC()), request.getAdvantage());

        if (!result.concentration() && spellEffects.persistsWithoutConcentration(payment.spellName())) {
            caster.setActiveSpell(payment.spellName());
            caster.setActiveSpellSlotLevel(payment.castLevel());
        }
        return response(encounter, result, payment.castLevel(), payment.source());
    }

    /**
     * The spells a participant can cast now, with what can pay for each (D164, D165): the DM may read anyone's,
     * a player only their own character's. It changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public List<SpellOption> spellOptions(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant participant = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
        return casterSpells.options(participant);
    }

    /** Uses the caster's concentration spell's effect again, or else its lasting spell's. */
    @Transactional
    public CastSpellResponse repeatSpellEffect(UUID encounterId, RepeatSpellEffectRequest request,
                                               UUID actorParticipantId, UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(caster, request.getOverrideSpellAttackBonus(),
                request.getOverrideSpellSaveDC());
        manualModifiers.require(encounter, userId, modifiers);

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

        manualModifiers.log(encounter, CombatActionType.SPELL_EFFECT_REPEAT, caster,
                caster.getDisplayName() + "'s " + spellName, userId, modifiers);
        SpellCaster.CastResult result = spellCaster.repeat(encounter, caster, spellName, slotLevel, targets,
                attackBonus(caster, request.getOverrideSpellAttackBonus()),
                saveDc(caster, request.getOverrideSpellSaveDC()), request.getAdvantage());
        return response(encounter, result, slotLevel, SpellSource.NONE);
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

    private CastSpellResponse response(Encounter encounter, SpellCaster.CastResult result, int castLevel,
                                       SpellSource source) {
        return CastSpellResponse.builder()
                .encounterState(encounterService.toResponse(encounterRepository.save(encounter)))
                .spellName(result.spellName())
                .slotLevelUsed(castLevel)
                .spellSource(source)
                .autoResolved(result.autoResolved())
                .resultSummary(result.summary())
                .targets(result.targets())
                .manualResolutionReason(result.manualReason())
                .build();
    }

    private void requireTargetCount(SpellSources.Payment payment, int targetCount) {
        OptionalInt max = spellEffects.maxTargets(payment.spellName(), payment.castLevel());
        if (max.isPresent() && targetCount > max.getAsInt()) {
            int limit = max.getAsInt();
            throw new ValidationException(payment.spellName() + " can target at most " + limit
                    + " creature" + (limit != 1 ? "s" : "") + " at level " + payment.castLevel()
                    + ", but " + targetCount + " targets were selected");
        }
    }

    // The caster's own numbers unless a Manual Modifier replaces them; a caster without them has +0 and DC 10
    private static int attackBonus(EncounterParticipant caster, Integer entered) {
        if (entered != null) return entered;
        return caster.getSpellAttackBonus() != null ? caster.getSpellAttackBonus() : 0;
    }

    private static int saveDc(EncounterParticipant caster, Integer entered) {
        if (entered != null) return entered;
        return caster.getSpellSaveDc() != null ? caster.getSpellSaveDc() : 10;
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
