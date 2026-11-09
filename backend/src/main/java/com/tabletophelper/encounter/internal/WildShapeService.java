package com.tabletophelper.encounter.internal;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.WildShapeHealRequest;
import com.tabletophelper.encounter.dto.WildShapeOptions;
import com.tabletophelper.encounter.state.WildShapeForm;
import com.tabletophelper.rules.Dice;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.BeastShapes;
import com.tabletophelper.rules.combat.Charge;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wild Shape's commands beside the pool's use (Druid 2, PHB p.66–67): the beasts a druid can become, for the picker,
 * and a Circle of the Moon druid's Combat Wild Shape healing (PHB p.69), a bonus action on its turn while transformed
 * that expends a spell slot to regain 1d8 hit points per level of the slot. Transforming and reverting go through the
 * Wild Shape pool ({@link WildShapeEffects}). The DM may act for anyone, a player for their own participant (D028); the
 * healing changes only hit points, slots and the turn economy, so undo restores it (D176).
 */
@Service
@RequiredArgsConstructor
public class WildShapeService {

    private final CombatAccess access;
    private final AccessPolicy accessPolicy;
    private final ActionEconomyKeeper economy;
    private final EffectApplier effectApplier;
    private final WildShapeEffects wildShape;
    private final Rng rng;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * The beasts the participant can become now: the DM may read anyone's, a player only their own character's. It
     * changes nothing, so it needs no lock and any status will do.
     */
    @Transactional(readOnly = true)
    public WildShapeOptions options(UUID encounterId, UUID participantId, UUID userId) {
        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found"));
        UUID campaignId = encounter.getCampaign().getId();
        accessPolicy.requireMember(campaignId, userId);
        EncounterParticipant participant = CombatAccess.target(encounter, participantId, userId);
        accessPolicy.requireControls(campaignId, participant.getControlledByUserId(), userId);
        return wildShape.options(participant);
    }

    /**
     * Combat Wild Shape's healing: everything is checked, and the bonus action charged, before the slot is spent and
     * the dice rolled.
     *
     * @throws ConflictException for a druid that isn't of the Circle of the Moon or isn't in Wild Shape, has no slot
     *                           of the level left, or a player's bonus action already taken
     */
    @Transactional
    public EncounterResponse heal(UUID encounterId, WildShapeHealRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant druid = CombatAccess.participant(encounter, request.participantId());
        access.requireTurnForActionCost(encounter, druid.getId(), userId, ActionCost.BONUS_ACTION);
        if (!WildShapeEffects.circleOfTheMoon(druid)) {
            throw new ConflictException(druid.getDisplayName() + " isn't a Circle of the Moon druid, whose Combat "
                    + "Wild Shape this is");
        }
        WildShapeForm form = WildShapeForm.of(druid).orElseThrow(() -> new ConflictException(
                druid.getDisplayName() + " can heal with Combat Wild Shape only while in Wild Shape"));
        int level = request.slotLevel();
        Map<String, SpellSlot> slots = druid.getSpellSlotsCurrent() != null
                ? new LinkedHashMap<>(druid.getSpellSlotsCurrent()) : new LinkedHashMap<>();
        String key = slotKey(slots, level, Boolean.TRUE.equals(request.pactSlot()));
        SpellSlot slot = slots.get(key);
        if (slot == null || slot.remaining() < 1) {
            throw new ConflictException(druid.getDisplayName() + " has no " + slotName(key, level) + " left");
        }

        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, druid.getId());
        economy.charge(encounter, druid, Charge.of(ActionCost.BONUS_ACTION, ownTurn), userId,
                new ActionEconomyKeeper.Command(CombatActionType.HEAL,
                        druid.getDisplayName() + "'s Combat Wild Shape (bonus action)"));
        slots.put(key, slot.spend());
        druid.setSpellSlotsCurrent(slots);

        Dice dice = BeastShapes.combatHealing(level);
        int rolled = dice.rollDice(rng);
        SpellSlot after = slots.get(key);
        effectApplier.heal(encounter, druid, druid, dice.total(rolled), "Combat Wild Shape as a " + form.beastName()
                + ", bonus action: " + slotName(key, level) + " (" + after.remaining() + "/" + after.total()
                + " left), " + dice.count() + "d8(" + rolled + ")");
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    // A spell slot of the level, or a pact slot of that level when asked for or when it is the only one there is
    private static String slotKey(Map<String, SpellSlot> slots, int level, boolean pact) {
        String spell = String.valueOf(level);
        String pactKey = "pact_" + level;
        if (pact) {
            return pactKey;
        }
        return slots.containsKey(spell) || !slots.containsKey(pactKey) ? spell : pactKey;
    }

    private static String slotName(String key, int level) {
        return key.startsWith("pact_") ? "level " + level + " pact slot" : "level " + level + " spell slot";
    }
}
