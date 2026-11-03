package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.SpellSlot;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A participant's spellcasting in combat: casting a spell paid for from a Spell Source, the same way for player
 * characters and monsters (D165); using a lasting spell's effect again; spell slots; and concentration set by
 * hand. A player acts only for their own character, casting only on its turn (D028) and only what the character
 * can cast now (D164). A class spell uses that class's save DC and attack bonus, and a race or feat spell the
 * caster's main ones. A spell attack bonus or save DC entered in place of the caster's is a Manual Modifier,
 * which a player may enter only while the campaign allows it.
 */
@Service
@RequiredArgsConstructor
public class SpellcastingService {

    private static final String CLASS_ORIGIN = "class:";

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
    private final CharacterService characterService;

    /**
     * Casts a spell for a player character or a monster: everything is checked before anything is spent, then
     * the Spell Source pays, and the spell is cast at the level that source gives.
     */
    @Transactional
    public CastSpellResponse castSpell(UUID encounterId, CastSpellRequest request, UUID actorParticipantId,
                                       UUID userId) {
        Encounter encounter = access.beginOnTurnFor(encounterId, actorParticipantId, userId);
        EncounterParticipant caster = CombatAccess.participant(encounter, actorParticipantId);
        manualModifiers.require(encounter, userId, ManualModifiers.forSpell(caster,
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC()));
        SpellSources.Payment payment = casterSpells.payment(caster, request.getSpellName(), request.getSpellSource(),
                request.getSlotLevel());
        requireTargetCount(payment, request.getTargetIds().size());
        List<EncounterParticipant> targets = CombatAccess.targets(encounter, request.getTargetIds(), userId);
        SpellNumbers own = numbers(caster, payment.origin());
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(own.attackBonus(), own.saveDc(),
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC());

        casterSpells.spend(encounter, caster, payment);
        manualModifiers.log(encounter, CombatActionType.SPELL_CAST, caster,
                caster.getDisplayName() + "'s " + payment.spellName(), userId, modifiers);
        SpellCaster.CastResult result = spellCaster.cast(encounter, caster, payment.spellName(), payment.castLevel(),
                targets, own.ability(), attackBonus(own, request.getOverrideSpellAttackBonus()),
                saveDc(own, request.getOverrideSpellSaveDC()), request.getAdvantage());

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
        manualModifiers.require(encounter, userId, ManualModifiers.forSpell(caster,
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC()));

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
        SpellNumbers own = numbers(caster, casterSpells.origin(caster, spellName));
        List<ManualModifiers.Use> modifiers = ManualModifiers.forSpell(own.attackBonus(), own.saveDc(),
                request.getOverrideSpellAttackBonus(), request.getOverrideSpellSaveDC());

        manualModifiers.log(encounter, CombatActionType.SPELL_EFFECT_REPEAT, caster,
                caster.getDisplayName() + "'s " + spellName, userId, modifiers);
        SpellCaster.CastResult result = spellCaster.repeat(encounter, caster, spellName, slotLevel, targets,
                own.ability(), attackBonus(own, request.getOverrideSpellAttackBonus()),
                saveDc(own, request.getOverrideSpellSaveDC()), request.getAdvantage());
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

    /**
     * A spell's own attack bonus and save DC, before any Manual Modifier, and the ability it is cast with, whose
     * modifier its damage and healing add; null where the caster has none.
     */
    private record SpellNumbers(Integer attackBonus, Integer saveDc, String ability) {
    }

    /**
     * The numbers a spell is cast with (D164): a player character's class spell uses that class's, worked out from
     * the live sheet as saves and resistances are; a race or feat spell, and a monster's, the caster's main ones,
     * with its race and feat spellcasting ability or the monster's spellcasting ability.
     */
    private SpellNumbers numbers(EncounterParticipant caster, String origin) {
        PlayerCharacter character = caster.getCharacter();
        if (character != null && origin != null && origin.startsWith(CLASS_ORIGIN)) {
            Optional<ClassSpellcasting> casting =
                    characterService.classSpellcasting(character, origin.substring(CLASS_ORIGIN.length()));
            if (casting.isPresent()) {
                return new SpellNumbers(casting.get().spellAttackBonus(), casting.get().spellSaveDc(),
                        casting.get().ability());
            }
        }
        // A player character's race and feat spells use its main ability, the chosen one or its first class's
        String ability = character != null ? characterService.mainSpellcastingAbility(character)
                : caster.getSpellcastingAbility();
        return new SpellNumbers(caster.getSpellAttackBonus(), caster.getSpellSaveDc(), ability);
    }

    // The spell's own numbers unless a Manual Modifier replaces them; a caster without them has +0 and DC 10
    private static int attackBonus(SpellNumbers own, Integer entered) {
        if (entered != null) return entered;
        return own.attackBonus() != null ? own.attackBonus() : 0;
    }

    private static int saveDc(SpellNumbers own, Integer entered) {
        if (entered != null) return entered;
        return own.saveDc() != null ? own.saveDc() : 10;
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
