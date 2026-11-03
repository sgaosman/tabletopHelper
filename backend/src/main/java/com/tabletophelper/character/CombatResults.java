package com.tabletophelper.character;

import com.tabletophelper.character.state.CombatTotals;
import com.tabletophelper.rules.combat.LifeState;

import java.util.List;
import java.util.UUID;

/**
 * What an encounter did to its player characters (D166): for each, the totals its participant joined with and ended
 * with, and how it ended, alive, Dying, Stable or dead. The encounter module publishes it when the DM completes an
 * encounter with its results applied, or removes a player character from one under way, and
 * {@link WriteBackService} applies it inside that transaction, so the dependency runs from encounter to character only
 * (D151). It also drives the End dialog's preview.
 *
 * @param characters one entry for each player participant, in the encounter's order
 */
public record CombatResults(UUID encounterId, List<CharacterResult> characters) {

    public CombatResults {
        characters = List.copyOf(characters);
    }

    /** One player participant's results. */
    public record CharacterResult(UUID characterId, CombatTotals joinedWith, CombatTotals endedWith,
                                  LifeState lifeState) {
    }
}
