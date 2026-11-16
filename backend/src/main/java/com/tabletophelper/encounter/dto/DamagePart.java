package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One damage type a target took and how much of it got through its defences, so a hit of two types reads
 * "12 slashing + 4 lightning" (D207's quick result). type is null for untyped damage.
 */
public record DamagePart(String type, @Schema(requiredMode = REQUIRED) int amount) {
}
