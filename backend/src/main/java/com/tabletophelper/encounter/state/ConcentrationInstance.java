package com.tabletophelper.encounter.state;

import java.util.UUID;

/**
 * One casting of a concentration spell by one caster, stored in {@code encounter_participants.concentration}
 * (D162). The conditions the casting creates carry its ID, and all of them end together when it ends.
 *
 * @param id             identifies the casting, so its conditions can name it
 * @param spell          the spell's name
 * @param slotLevel      the slot level it was cast with; null when unknown, as for concentration set by hand
 * @param startedRound   the round it started in
 * @param durationRounds how many rounds it lasts; null when unknown
 */
public record ConcentrationInstance(UUID id, String spell, Integer slotLevel, int startedRound,
                                    Integer durationRounds) {

    /** A new casting, with an ID of its own. */
    public static ConcentrationInstance start(String spell, Integer slotLevel, int startedRound,
                                              Integer durationRounds) {
        return new ConcentrationInstance(UUID.randomUUID(), spell, slotLevel, startedRound, durationRounds);
    }
}
