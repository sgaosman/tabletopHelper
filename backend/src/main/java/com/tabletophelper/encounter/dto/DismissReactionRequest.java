package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Lets a reaction offer pass without taking it (D216).
 *
 * @param offerId the offer, as the encounter lists it
 */
public record DismissReactionRequest(@NotNull UUID offerId) {
}
