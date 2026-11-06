package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.EncounterRepository;
import com.tabletophelper.encounter.EncounterService;
import com.tabletophelper.encounter.dto.EncounterResponse;
import com.tabletophelper.encounter.dto.TurnEconomyRequest;
import com.tabletophelper.encounter.state.TurnEconomy;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The DM's edit of a participant's turn economy (D174): any part of it, for what the app doesn't model, such as
 * Haste's extra action, Slow, surprise, or a correction. The DM's alone; it lasts until the start of the
 * participant's next turn, which starts it afresh, and it is logged with what changed.
 */
@Service
@RequiredArgsConstructor
public class TurnEconomyEditService {

    // More than any turn could have: four attacks a Fighter 20 gets, with Haste and Action Surge twice over
    private static final int MOST = 10;

    private final CombatAccess access;
    private final CombatLogWriter combatLog;
    private final EncounterRepository encounterRepository;
    private final EncounterService encounterService;

    /**
     * Sets the participant's economy, logged as TURN_ECONOMY_SET with each change, such as "actions 1 → 2".
     *
     * @throws ValidationException for a count below 0 or above 10, or an unknown participant
     */
    @Transactional
    public EncounterResponse setTurnEconomy(UUID encounterId, TurnEconomyRequest request, UUID userId) {
        Encounter encounter = access.beginAsDm(encounterId, userId);
        TurnEconomy after = request.turnEconomy();
        requireCount("actions", after.actions());
        requireCount("actionsUsed", after.actionsUsed());
        requireCount("attacksLeft", after.attacksLeft());
        EncounterParticipant participant = CombatAccess.participant(encounter, request.participantId());
        TurnEconomy before = participant.getTurnEconomy() != null ? participant.getTurnEconomy() : TurnEconomy.fresh();
        participant.setTurnEconomy(after);
        List<String> changes = changes(before, after);
        combatLog.write(encounter, CombatActionType.TURN_ECONOMY_SET, "The DM sets " + participant.getDisplayName()
                + "'s turn economy: " + (changes.isEmpty() ? "no change" : String.join(", ", changes)), null,
                participant);
        return encounterService.toResponse(encounterRepository.save(encounter));
    }

    private static void requireCount(String name, int value) {
        if (value < 0 || value > MOST) {
            throw new ValidationException("turnEconomy." + name + " must be from 0 to " + MOST);
        }
    }

    // Each part that differs, in the record's order, as "bonus action used no → yes"
    static List<String> changes(TurnEconomy before, TurnEconomy after) {
        List<String> changes = new ArrayList<>();
        for (RecordComponent component : TurnEconomy.class.getRecordComponents()) {
            Object was = value(component, before);
            Object is = value(component, after);
            if (!Objects.equals(was, is)) {
                changes.add(words(component.getName()) + " " + text(was) + " → " + text(is));
            }
        }
        return changes;
    }

    private static Object value(RecordComponent component, TurnEconomy economy) {
        try {
            return component.getAccessor().invoke(economy);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Can't read the turn economy's " + component.getName(), e);
        }
    }

    // "bonusActionUsed" as "bonus action used"
    private static String words(String name) {
        return name.replaceAll("([A-Z])", " $1").toLowerCase();
    }

    private static String text(Object value) {
        return value instanceof Boolean flag ? (flag ? "yes" : "no") : String.valueOf(value);
    }
}
