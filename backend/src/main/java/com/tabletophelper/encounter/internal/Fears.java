package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.rules.combat.AttackRules;
import com.tabletophelper.rules.combat.Fear;
import com.tabletophelper.rules.combat.LifeState;
import com.tabletophelper.rules.combat.OngoingModifiers;
import com.tabletophelper.rules.combat.RollMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A participant's fears as the encounter stands (PHB p.290, D201): for each frightened entry, its source, recorded as the
 * entry's {@code sourceParticipantId} when a spell, a monster's action, Channel Divinity or the DM gave it, and whether
 * that source is within the creature's line of sight by {@link Fear#sight}. While one is, the creature has
 * disadvantage on its attack rolls, named "attacker frightened (Adult Red Dragon in sight)", and on the ability checks
 * the app rolls ({@link AbilityChecks}), named "frightened (Adult Red Dragon in sight)". Its saving throws aren't
 * affected. Worked out each time a roll needs it, so the source's death, invisibility or banishment counts at once.
 */
public final class Fears {

    private Fears() {}

    /**
     * One frightened entry on a creature.
     *
     * @param condition the entry
     * @param source    the creature it is frightened of, or for a fear of an illusion the spell's caster; null when none
     *                  is recorded or the source has left the encounter
     * @param label     what it is frightened of, such as "Adult Red Dragon" or "Merlin's Illusory Dragon"; null when
     *                  no source is recorded or the source has left
     * @param sight     whether that source is in sight, and why not
     */
    public record Status(ConditionEntry condition, EncounterParticipant source, String label, Fear.Sight sight) {

        public boolean inSight() {
            return sight.inSight();
        }
    }

    /** The creature's frightened entries, each with its source and whether it is in sight; empty for none. */
    public static List<Status> of(EncounterParticipant creature) {
        List<ConditionEntry> conditions = creature.getActiveConditions();
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        AttackRules.Side side = CreatureStats.side(creature);
        List<Status> statuses = new ArrayList<>();
        for (ConditionEntry condition : conditions) {
            if (condition == null || !Fear.isFrightened(condition.name())) {
                continue;
            }
            statuses.add(status(creature.getEncounter(), side, condition));
        }
        return statuses;
    }

    /**
     * The disadvantage the creature's fears give its attack rolls: one source for each creature it fears that is in
     * sight, such as "attacker frightened (Adult Red Dragon in sight)", or "attacker frightened" for a fear with no
     * recorded source; none for no creature, such as the DM attacking by hand.
     */
    public static List<AttackRules.Source> attackSources(EncounterParticipant attacker) {
        return attacker == null ? List.of() : sources(attacker, "attacker ");
    }

    /**
     * The disadvantage the creature's fears give the ability checks the app rolls, as {@link #attackSources} without
     * "attacker", such as "frightened (Adult Red Dragon in sight)".
     */
    public static List<AttackRules.Source> checkSources(EncounterParticipant creature) {
        return sources(creature, "");
    }

    /** Whether any of the creature's fears has its source in sight. */
    public static boolean anyInSight(EncounterParticipant creature) {
        return of(creature).stream().anyMatch(Status::inSight);
    }

    // One source per creature feared in sight, so two fears of one creature name it once
    private static List<AttackRules.Source> sources(EncounterParticipant creature, String prefix) {
        Set<String> reasons = new LinkedHashSet<>();
        for (Status status : of(creature)) {
            if (status.inSight()) {
                reasons.add(prefix + Fear.FRIGHTENED + (status.label() != null ? " (" + status.label() + " in sight)"
                        : ""));
            }
        }
        return reasons.stream().map(reason -> new AttackRules.Source(RollMode.DISADVANTAGE, reason)).toList();
    }

    private static Status status(Encounter encounter, AttackRules.Side side, ConditionEntry condition) {
        UUID sourceId = condition.sourceParticipantId();
        Optional<EncounterParticipant> found = sourceId == null || encounter == null ? Optional.empty()
                : encounter.getParticipants().stream().filter(p -> sourceId.equals(p.getId())).findFirst();
        boolean markedOut = condition.sourceMarkedOutOfSight();
        // A fear of an illusion the spell creates: its caster needn't be seen (Phantasmal Killer, PHB p.265)
        if (Fear.illusorySource(condition.sourceSpellName())) {
            String label = found.map(caster -> caster.getDisplayName() + "'s " + condition.sourceSpellName())
                    .orElse(condition.sourceSpellName());
            return new Status(condition, found.orElse(null), label, Fear.sight(side, Fear.Source.NONE, markedOut));
        }
        if (sourceId == null) {
            return new Status(condition, null, null, Fear.sight(side, Fear.Source.NONE, markedOut));
        }
        if (found.isEmpty()) {
            // The source has left the encounter, as a removed participant has
            return new Status(condition, null, null, Fear.sight(side, new Fear.Source(null, false), markedOut));
        }
        EncounterParticipant source = found.get();
        return new Status(condition, source, source.getDisplayName(),
                Fear.sight(side, new Fear.Source(sourceSide(source), present(source)), markedOut));
    }

    // The source's side as the frightened creature sees it: its invisibility is no use under Faerie Fire (PHB p.239)
    private static AttackRules.Side sourceSide(EncounterParticipant source) {
        AttackRules.Side side = CreatureStats.side(source);
        return OngoingModifiers.revealed(ActiveModifiers.modifiersOn(source)) ? side.revealed() : side;
    }

    /*
     * Whether the source is still there to be seen: not dead, not a summoned creature that has disappeared (D182), not
     * banished or taken to another plane (PHB p.217, p.266) and not on the Ethereal Plane through Blink (PHB p.219).
     */
    private static boolean present(EncounterParticipant source) {
        return source.getLifeState() != LifeState.DEAD && !EncounterTurns.dismissed(source)
                && !CombatAccess.banished(source) && !DefensiveSpells.ethereal(source);
    }
}
