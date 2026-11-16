package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A potion drunk, or given to another creature, as an action on the participant's turn (PHB p.153, D210).
 *
 * @param participantId who uses it: a player character, from whose sheet it comes
 * @param itemName      the inventory entry, by its name on the sheet
 * @param targetId      the creature that drinks it; left out, the user drinks it. There is no map, so any creature
 *                      the user picks is taken to be within reach
 */
public record UseItemRequest(
        @NotNull UUID participantId,
        @NotBlank @Size(max = 200) String itemName,
        UUID targetId
) {
}
