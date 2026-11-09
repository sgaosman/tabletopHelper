package com.tabletophelper.encounter.state;

import java.util.UUID;

/**
 * A combat log entry a command changed, as it was before (M14): Legendary Resistance resolves the entry of the save
 * it turns into a success (D163), and undoing it leaves the entry unresolved again.
 *
 * @param logId      the entry
 * @param lrResolved its lr_resolved before the command
 */
public record LogPatch(UUID logId, Boolean lrResolved) {
}
