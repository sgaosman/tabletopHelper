package com.tabletophelper.character.dto;

import com.tabletophelper.rules.combat.LifeState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What completing an encounter with its results applied would write back to one player character's sheet, as the
 * sheet stands now (D166), for the DM's End dialog. It changes nothing. Only the slots and pools that would change are
 * listed.
 *
 * @param characterId  the character
 * @param name         the character's name
 * @param lifeState    how its participant ended: Alive, Dying or Stable is written back alive, at 0 hit points for the
 *                     last two, and Dead marks it dead
 * @param hpMax        the sheet's hit point maximum
 * @param hpBefore     the sheet's hit points now
 * @param hpAfter      its hit points once the encounter's change is applied, between 0 and the maximum
 * @param hpTempBefore the sheet's temporary hit points now
 * @param hpTempAfter  the temporary hit points the participant has left, which the sheet takes
 * @param deadBefore   whether the sheet already marks it dead
 * @param deadAfter    whether it is dead once the results are applied
 * @param slots        the spell slots whose used count would change
 * @param pools        the resource pools whose current uses would change
 */
public record CombatResultPreview(
        @Schema(requiredMode = REQUIRED) UUID characterId,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) LifeState lifeState,
        @Schema(requiredMode = REQUIRED) int hpMax,
        @Schema(requiredMode = REQUIRED) int hpBefore,
        @Schema(requiredMode = REQUIRED) int hpAfter,
        @Schema(requiredMode = REQUIRED) int hpTempBefore,
        @Schema(requiredMode = REQUIRED) int hpTempAfter,
        @Schema(requiredMode = REQUIRED) boolean deadBefore,
        @Schema(requiredMode = REQUIRED) boolean deadAfter,
        @Schema(requiredMode = REQUIRED) List<SlotChange> slots,
        @Schema(requiredMode = REQUIRED) List<PoolChange> pools,
        @Schema(requiredMode = REQUIRED) int exhaustionBefore,
        @Schema(requiredMode = REQUIRED) int exhaustionAfter
) {

    /**
     * A spell slot's used count before and after.
     *
     * @param slot the slot's key: "1" to "9", or "pact_N" for pact slots
     */
    public record SlotChange(
            @Schema(requiredMode = REQUIRED) String slot,
            @Schema(requiredMode = REQUIRED) int max,
            @Schema(requiredMode = REQUIRED) int usedBefore,
            @Schema(requiredMode = REQUIRED) int usedAfter
    ) {
    }

    /** A resource pool's current uses before and after. */
    public record PoolChange(
            @Schema(requiredMode = REQUIRED) String poolId,
            @Schema(requiredMode = REQUIRED) String displayName,
            @Schema(requiredMode = REQUIRED) int maxUses,
            @Schema(requiredMode = REQUIRED) int currentBefore,
            @Schema(requiredMode = REQUIRED) int currentAfter
    ) {
    }
}
