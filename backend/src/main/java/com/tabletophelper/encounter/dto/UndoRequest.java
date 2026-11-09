package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The DM's undo of the newest combat commands (M14).
 *
 * @param expectedCommandId the newest command as the DM's dialog showed it; if another command has been taken since,
 *                          the undo is refused, so the DM never undoes something they didn't review
 * @param steps             how many commands to undo, newest first; 1 when left out
 */
public record UndoRequest(
        @NotNull UUID expectedCommandId,
        @Min(1) @Max(20) Integer steps
) {
}
