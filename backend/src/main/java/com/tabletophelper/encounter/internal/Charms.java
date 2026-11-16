package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.combat.Charm;
import com.tabletophelper.shared.ConflictException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A participant's charms as the encounter stands (PHB p.290, D224): for each charmed entry, its charmer, recorded as the
 * entry's {@code sourceParticipantId} when a spell, a monster's action or the DM gave it, as a fear's source is (D201).
 * A charmed creature can't attack its charmer or target it with harmful abilities or magical effects: a weapon attack, a
 * monster's action or a harmful spell aimed at the charmer is refused for a player with a 409 that says why, and the DM,
 * who is never refused (D174), is warned in the combat log and the command goes ahead.
 */
public final class Charms {

    private Charms() {}

    /** The creatures among the targets the actor is charmed by; empty for none, or for a charm with no source recorded. */
    public static List<EncounterParticipant> charmersAmong(EncounterParticipant actor,
                                                           Collection<EncounterParticipant> targets) {
        if (actor == null || actor.getActiveConditions() == null || targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<UUID> charmers = actor.getActiveConditions().stream()
                .filter(c -> c != null && Charm.isCharmed(c.name()) && c.sourceParticipantId() != null)
                .map(ConditionEntry::sourceParticipantId)
                .toList();
        if (charmers.isEmpty()) {
            return List.of();
        }
        return targets.stream().filter(t -> t != null && charmers.contains(t.getId())).distinct().toList();
    }

    /** Why the actor can't aim the command at one of the targets, its charmer, or empty when it can. */
    public static Optional<String> refusal(EncounterParticipant actor, Collection<EncounterParticipant> targets) {
        return charmersAmong(actor, targets).stream().findFirst()
                .map(charmer -> Charm.refusal(actor.getDisplayName(), charmer.getDisplayName()));
    }

    /**
     * Refuses a player's command aimed at the actor's charmer with a 409, and lets the DM's through with a warning in the
     * combat log, an entry about the charmer so the players' log leaves it out while the charmer is hidden (D157).
     * Called before anything is charged, rolled or spent.
     */
    public static void enforce(Encounter encounter, EncounterParticipant actor,
                               Collection<EncounterParticipant> targets, UUID userId, CombatLogWriter combatLog,
                               CombatActionType type) {
        List<EncounterParticipant> charmers = charmersAmong(actor, targets);
        if (charmers.isEmpty()) {
            return;
        }
        if (!CombatAccess.isDm(encounter, userId)) {
            throw new ConflictException(Charm.refusal(actor.getDisplayName(), charmers.getFirst().getDisplayName()));
        }
        for (EncounterParticipant charmer : charmers) {
            combatLog.write(encounter, type, "Charmed: " + Charm.refusal(actor.getDisplayName(),
                    charmer.getDisplayName()) + "; the DM's command goes ahead.", actor, charmer);
        }
    }
}
