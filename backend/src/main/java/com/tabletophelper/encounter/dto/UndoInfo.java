package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What the DM's Undo would take back (M14): the newest combat command since the last barrier, and the ones before it
 * that "Undo to here" can take with it. Only in the DM View of an Active encounter.
 *
 * @param commandId      the newest command, which an undo request names as expectedCommandId
 * @param kind           its kind, such as TURN_ADVANCE
 * @param summary        what it did
 * @param entryCount     how many combat log entries it wrote
 * @param stepsAvailable how many commands could be undone, back to the last barrier
 * @param recent         the newest commands, newest first, at most as many as one undo may take
 */
public record UndoInfo(
        @Schema(requiredMode = REQUIRED) UUID commandId,
        @Schema(requiredMode = REQUIRED) String kind,
        @Schema(requiredMode = REQUIRED) String summary,
        @Schema(requiredMode = REQUIRED) int entryCount,
        @Schema(requiredMode = REQUIRED) long stepsAvailable,
        @Schema(requiredMode = REQUIRED) List<UndoableCommand> recent) {
}
