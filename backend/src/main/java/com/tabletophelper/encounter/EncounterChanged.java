package com.tabletophelper.encounter;

import java.util.UUID;

/**
 * A command changed the encounter (D160). Services publish it inside their transaction, and
 * {@link EncounterBroadcaster} publishes the encounter's state only once that transaction commits, so
 * clients never see a change that was rolled back.
 */
public record EncounterChanged(UUID encounterId) {
}
