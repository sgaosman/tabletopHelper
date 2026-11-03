package com.tabletophelper.character;

import java.util.UUID;

/**
 * Whether a character is in an encounter that hasn't ended (D151, D166). The encounter module answers it, so the
 * character module depends on nothing there: resting, levelling and the choices a level brings wait while the
 * character is in an Active or Paused encounter, and the sheet shows why.
 */
public interface LiveEncounters {

    /** Whether the character is a participant in an Active or Paused encounter. */
    boolean includes(UUID characterId);

    /**
     * Whether the character is a participant in an encounter that hasn't been completed, a Preparing one included:
     * such a character can't be deleted.
     */
    boolean includesUnfinished(UUID characterId);
}
