package com.tabletophelper.character.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * How a character's Armour Class adds up (M13.5): the armour or unarmoured formula, the abilities it adds, the
 * shield, flat bonuses and the DM's adjustments, each a part, and what the armour worn costs the character.
 *
 * @param total    the AC, the parts added up: the derived AC plus the DM's AC adjustments
 * @param parts    each part in order, such as "Chain Mail" 16, "Shield" 2 and "DM adjustments" 1
 * @param warnings what the armour worn costs, such as armour worn without proficiency or heavy armour's Strength
 * @param armourEntries the positions in the inventory of the carried entries that are armour or a shield, equipped or
 *                      not, which the sheet offers to equip
 */
public record ArmourClassBreakdown(
        @Schema(requiredMode = REQUIRED) int total,
        @Schema(requiredMode = REQUIRED) List<Part> parts,
        @Schema(requiredMode = REQUIRED) List<String> warnings,
        @Schema(requiredMode = REQUIRED) List<Integer> armourEntries
) {

    public ArmourClassBreakdown {
        parts = List.copyOf(parts);
        warnings = List.copyOf(warnings);
        armourEntries = List.copyOf(armourEntries);
    }

    /** One part of the AC, such as "DEX (max 2)" +2. */
    @Schema(name = "ArmourClassPart")
    public record Part(
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) int amount
    ) {
    }
}
