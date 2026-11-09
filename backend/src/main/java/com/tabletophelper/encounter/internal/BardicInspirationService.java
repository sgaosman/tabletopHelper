package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.BardicInspirationRequest;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.HeldDie;
import com.tabletophelper.rules.Rng;
import com.tabletophelper.rules.combat.ActionCost;
import com.tabletophelper.shared.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A held Bardic Inspiration die used by its holder (PHB p.53, M17): the DM for anyone, a player for their own
 * participant, on any turn, since the die costs nothing to roll. The die is added after the d20 is seen, before the
 * result is known, so the holder can keep it for its next failed saving throw, which the server rolls and adds when it
 * fails, or roll it now for an ability check or a save the DM resolves by hand. An attack adds it through the attack
 * request. Each is an undoable command (D176).
 */
@Service
@RequiredArgsConstructor
public class BardicInspirationService {

    private final CombatAccess access;
    private final CombatLogWriter combatLog;
    private final Rng rng;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Uses the participant's die as asked.
     *
     * @throws ConflictException when the participant holds no Bardic Inspiration die
     */
    @Transactional
    public EncounterResponse use(UUID encounterId, BardicInspirationRequest request, UUID userId) {
        Encounter encounter = access.beginForCost(encounterId, request.participantId(), userId, ActionCost.FREE);
        EncounterParticipant holder = CombatAccess.participant(encounter, request.participantId());
        ConditionEntry held = EffectApplier.inspiration(holder).orElseThrow(() -> new ConflictException(
                holder.getDisplayName() + " holds no Bardic Inspiration die"));
        HeldDie die = held.heldDie();
        String name = holder.getDisplayName();
        switch (request.use()) {
            case ROLL -> {
                int roll = rng.roll(die.sides());
                List<ConditionEntry> conditions = new ArrayList<>(holder.getActiveConditions());
                conditions.remove(held);
                holder.setActiveConditions(conditions.isEmpty() ? null : conditions);
                combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.POOL_USE,
                        name + " rolls their Bardic Inspiration die: d" + die.sides() + "(" + roll + "), added to the "
                                + "ability check or saving throw the DM is resolving"), holder, holder)
                        .rollValue(roll).rollTotal(roll));
            }
            case ON_FAILED_SAVE, KEEP -> {
                boolean armed = request.use() == BardicInspirationRequest.Use.ON_FAILED_SAVE;
                if (die.onFailedSave() == armed) {
                    break;
                }
                List<ConditionEntry> conditions = new ArrayList<>(holder.getActiveConditions());
                conditions.set(conditions.indexOf(held), held.withHeldDie(die.withOnFailedSave(armed)));
                holder.setActiveConditions(conditions);
                combatLog.write(encounter, CombatActionType.POOL_USE, armed
                        ? name + " keeps their Bardic Inspiration d" + die.sides() + " for their next failed saving "
                                + "throw"
                        : name + " keeps their Bardic Inspiration d" + die.sides() + " for an attack roll or a roll by "
                                + "hand", holder, holder);
            }
        }
        return encounterService.toResponse(encounterRepository.save(encounter));
    }
}
