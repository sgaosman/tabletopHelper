package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A Repeat Save the player rolls for their character when the campaign prompts them (D162).
 *
 * @param participantId the creature with the condition
 * @param conditionId   the condition's entry, whose Repeat Save is waiting for the roll
 */
public record RepeatSaveRequest(@NotNull UUID participantId, @NotNull UUID conditionId) {}
