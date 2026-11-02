package com.tabletophelper.encounter;

import com.tabletophelper.shared.ConflictException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * The encounter lifecycle (D166): Preparing → Active ⇄ Paused → Completed. The DM's start, pause, resume
 * and complete commands each make one {@link Transition}, and any other move is a 409. Completed is final,
 * so an encounter completes, and syncs its pools back, only once. Combat needs an Active encounter, so a
 * Paused one can only resume or complete.
 */
public final class EncounterLifecycle {

    /** A DM command that changes an encounter's status: the statuses it may start from, and where it ends. */
    public enum Transition {
        START("start", EncounterStatus.ACTIVE, EnumSet.of(EncounterStatus.PREPARING)),
        PAUSE("pause", EncounterStatus.PAUSED, EnumSet.of(EncounterStatus.ACTIVE)),
        RESUME("resume", EncounterStatus.ACTIVE, EnumSet.of(EncounterStatus.PAUSED)),
        COMPLETE("complete", EncounterStatus.COMPLETED, EnumSet.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED));

        private final String verb;
        private final EncounterStatus to;
        private final Set<EncounterStatus> from;

        Transition(String verb, EncounterStatus to, Set<EncounterStatus> from) {
            this.verb = verb;
            this.to = to;
            this.from = from;
        }

        public EncounterStatus to() {
            return to;
        }
    }

    private EncounterLifecycle() {
    }

    /** Whether some transition moves an encounter straight from one status to the other. */
    public static boolean allows(EncounterStatus from, EncounterStatus to) {
        return Arrays.stream(Transition.values())
                .anyMatch(transition -> transition.to == to && transition.from.contains(from));
    }

    /** Refuses the transition with a 409 unless the encounter's status allows it; changes nothing. */
    public static void require(Encounter encounter, Transition transition) {
        EncounterStatus status = encounter.getStatus();
        if (transition.from.contains(status)) {
            return;
        }
        if (status == transition.to) {
            throw new ConflictException("The encounter is already " + describe(status));
        }
        throw new ConflictException("Cannot " + transition.verb + " an encounter that is " + describe(status));
    }

    /** Makes the transition, or refuses it with a 409 and leaves the status as it was. */
    public static void apply(Encounter encounter, Transition transition) {
        require(encounter, transition);
        encounter.setStatus(transition.to);
    }

    /** Combat commands need an Active encounter; any other status is a 409 that says why. */
    public static void requireActive(Encounter encounter) {
        switch (encounter.getStatus()) {
            case ACTIVE -> {
            }
            case PAUSED -> throw new ConflictException("The encounter is paused until the DM resumes it");
            case PREPARING -> throw new ConflictException("The encounter hasn't started yet");
            case COMPLETED -> throw new ConflictException("The encounter has ended");
        }
    }

    private static String describe(EncounterStatus status) {
        return switch (status) {
            case PREPARING -> "still being prepared";
            case ACTIVE -> "active";
            case PAUSED -> "paused";
            case COMPLETED -> "completed";
        };
    }
}
