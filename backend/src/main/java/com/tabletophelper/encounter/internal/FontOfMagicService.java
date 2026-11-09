package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.FlexibleCasting;
import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.FontOfMagicUseRequest;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.rules.combat.Charge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.UUID;

/**
 * A sorcerer's Flexible Casting at the table (PHB p.101, M18): as a bonus action on their turn, a spell slot becomes
 * sorcery points equal to its level, or sorcery points create a slot of 1st to 5th level, which Write-back carries to
 * the sheet until a long rest ends it. The exchange is the one the sheet makes ({@link FlexibleCasting}); the DM may
 * make it for anyone, a player for their own participant on its turn (D028, D174). It is logged as a pool use, and
 * since it changes only slots, pools and the turn economy, undo restores it (D176).
 */
@Service
@RequiredArgsConstructor
public class FontOfMagicService {

    private final CombatAccess access;
    private final ActionEconomyKeeper economy;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Makes the exchange: everything is checked, and the bonus action charged, before the slots and points change.
     *
     * @throws com.tabletophelper.shared.ConflictException   for a participant without sorcery points, no slot of the
     *                                                       level left, points at their maximum or too few, or a
     *                                                       player's bonus action already taken
     * @throws com.tabletophelper.shared.ValidationException for a level Flexible Casting can't create
     */
    @Transactional
    public EncounterResponse use(UUID encounterId, FontOfMagicUseRequest request, UUID userId) {
        Encounter encounter = access.beginFor(encounterId, request.participantId(), userId);
        EncounterParticipant sorcerer = CombatAccess.participant(encounter, request.participantId());
        access.requireTurnForActionCost(encounter, sorcerer.getId(), userId, ActionCost.BONUS_ACTION);
        FlexibleCasting.Exchange exchange = FlexibleCasting.exchange(sorcerer.getDisplayName(),
                sorcerer.getSpellSlotsCurrent(), sorcerer.getResourcePoolsCurrent(), request.direction(),
                request.slotLevel());

        boolean ownTurn = EncounterTurns.isCurrentTurn(encounter, sorcerer.getId());
        economy.charge(encounter, sorcerer, Charge.of(ActionCost.BONUS_ACTION, ownTurn), userId,
                new ActionEconomyKeeper.Command(CombatActionType.POOL_USE,
                        sorcerer.getDisplayName() + "'s Flexible Casting (bonus action)"));
        sorcerer.setSpellSlotsCurrent(exchange.slots());
        sorcerer.setResourcePoolsCurrent(new ArrayList<>(exchange.pools()));
        combatLog.write(encounter, CombatActionType.POOL_USE, sorcerer.getDisplayName() + " uses Flexible Casting "
                + "(bonus action) and " + exchange.description(), sorcerer, null);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
