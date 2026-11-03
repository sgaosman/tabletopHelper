package com.tabletophelper.encounter.dto;

/**
 * The DM's choice on completing an encounter (D166), made once for the whole encounter.
 *
 * @param applyResults whether what the encounter changed for each player character is written back to their sheets;
 *                     true when left out, as with no body at all. False writes nothing back, pool uses included.
 */
public record EndEncounterRequest(Boolean applyResults) {

    /** The choice, applying the results unless the DM declined. */
    public boolean applies() {
        return !Boolean.FALSE.equals(applyResults);
    }
}
