package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The DM's order for participants tied on initiative and DEX (D163).
 *
 * @param participantIds every participant in the tie, in the order they should act
 */
public record TieOrderRequest(@NotNull List<@NotNull UUID> participantIds) {}
