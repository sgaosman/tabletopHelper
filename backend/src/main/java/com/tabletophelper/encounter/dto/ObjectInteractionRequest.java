package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * An interaction with an object on the participant's turn, such as drawing a sword or opening a door: the first
 * each turn is free (PHB p.190, D174).
 *
 * @param participantId who interacts
 * @param description   what it does, for the log
 */
public record ObjectInteractionRequest(
        @NotNull UUID participantId,
        @NotBlank @Size(max = 500) String description
) {
}
