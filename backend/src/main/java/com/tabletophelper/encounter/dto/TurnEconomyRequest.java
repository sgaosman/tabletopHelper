package com.tabletophelper.encounter.dto;

import com.tabletophelper.encounter.state.TurnEconomy;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The DM's edit of a participant's turn economy (D174), for what the app doesn't model, such as Haste's extra
 * action, Slow, surprise or a correction. It replaces the whole economy.
 *
 * @param participantId whose economy it is
 * @param turnEconomy   the economy it has from now until the start of its next turn
 */
public record TurnEconomyRequest(
        @NotNull UUID participantId,
        @NotNull TurnEconomy turnEconomy
) {
}
