package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A combat command the DM may undo (M14).
 *
 * @param commandId  the command, which its combat log entries name
 * @param kind       the action type of the entry that sums it up, such as SPELL_CAST or TURN_ADVANCE
 * @param summary    what it did, such as "Vex casts Fireball"
 * @param entryCount how many combat log entries it wrote; a command such as a lair status change writes none
 */
public record UndoableCommand(
        @Schema(requiredMode = REQUIRED) UUID commandId,
        @Schema(requiredMode = REQUIRED) String kind,
        @Schema(requiredMode = REQUIRED) String summary,
        @Schema(requiredMode = REQUIRED) int entryCount) {
}
