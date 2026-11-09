package com.tabletophelper.encounter.state;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.rules.combat.LifeState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A participant's combat state as a command found it, which undo puts back (M14). Only what combat changes is here:
 * the setup fields (name, visibility, initiative, sort order, AC, HP maximum, known spells, joined_with) are never
 * restored, so a rename or a hide made after the command stays.
 *
 * @param id                   the participant
 * @param hpCurrent            its hit points
 * @param hpTemp               its temporary hit points
 * @param lifeState            alive, dying, stable or dead (D161)
 * @param deathSaveSuccesses   its death save successes
 * @param deathSaveFailures    its death save failures
 * @param deathSaveDue         a Dying PC's death save is due (D163)
 * @param activeConditions     its conditions
 * @param concentration        the casting it concentrates on
 * @param activeSpell          its active spell
 * @param activeSpellSlotLevel that spell's slot level
 * @param spellSlotsCurrent    its spell slots
 * @param resourcePoolsCurrent its resource pools: legendary actions, Legendary Resistance and recharges among them
 * @param turnEconomy          what it has spent of its turn (D174)
 * @param lastLairActionUsed   the lair action it used last (D163)
 */
public record ParticipantCombatState(
        UUID id,
        Integer hpCurrent,
        Integer hpTemp,
        LifeState lifeState,
        Integer deathSaveSuccesses,
        Integer deathSaveFailures,
        Boolean deathSaveDue,
        List<ConditionEntry> activeConditions,
        ConcentrationInstance concentration,
        String activeSpell,
        Integer activeSpellSlotLevel,
        Map<String, SpellSlot> spellSlotsCurrent,
        List<ResourcePoolEntry> resourcePoolsCurrent,
        TurnEconomy turnEconomy,
        String lastLairActionUsed) {

    /** The participant's combat state now, its lists and maps copied. */
    public static ParticipantCombatState of(EncounterParticipant participant) {
        return new ParticipantCombatState(
                participant.getId(),
                participant.getHpCurrent(),
                participant.getHpTemp(),
                participant.getLifeState(),
                participant.getDeathSaveSuccesses(),
                participant.getDeathSaveFailures(),
                participant.getDeathSaveDue(),
                copy(participant.getActiveConditions()),
                participant.getConcentration(),
                participant.getActiveSpell(),
                participant.getActiveSpellSlotLevel(),
                participant.getSpellSlotsCurrent() != null ? new LinkedHashMap<>(participant.getSpellSlotsCurrent()) : null,
                copy(participant.getResourcePoolsCurrent()),
                participant.getTurnEconomy(),
                participant.getLastLairActionUsed());
    }

    /** Puts the state back on the participant, with lists and maps of its own that later commands may change. */
    public void restore(EncounterParticipant participant) {
        participant.setHpCurrent(hpCurrent);
        participant.setHpTemp(hpTemp);
        participant.setLifeState(lifeState != null ? lifeState : LifeState.ALIVE);
        participant.setDeathSaveSuccesses(deathSaveSuccesses);
        participant.setDeathSaveFailures(deathSaveFailures);
        participant.setDeathSaveDue(Boolean.TRUE.equals(deathSaveDue));
        participant.setActiveConditions(copy(activeConditions));
        participant.setConcentration(concentration);
        participant.setActiveSpell(activeSpell);
        participant.setActiveSpellSlotLevel(activeSpellSlotLevel);
        participant.setSpellSlotsCurrent(spellSlotsCurrent != null ? new LinkedHashMap<>(spellSlotsCurrent) : null);
        participant.setResourcePoolsCurrent(copy(resourcePoolsCurrent));
        participant.setTurnEconomy(turnEconomy != null ? turnEconomy : TurnEconomy.fresh());
        participant.setLastLairActionUsed(lastLairActionUsed);
    }

    private static <T> List<T> copy(List<T> list) {
        return list != null ? new ArrayList<>(list) : null;
    }
}
