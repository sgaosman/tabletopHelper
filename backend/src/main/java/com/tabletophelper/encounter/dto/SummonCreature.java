package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Creatures of one stat block a summoning brings (D182).
 *
 * @param monsterId the monster row, from the summon options' candidates
 * @param count     how many of it; 1 when left out
 */
public record SummonCreature(
        @Schema(requiredMode = REQUIRED) @NotNull UUID monsterId,
        @Min(1) @Max(32) Integer count) {

    /** The count, 1 when left out. */
    public int countOrOne() {
        return count != null ? count : 1;
    }
}
