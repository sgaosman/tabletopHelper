package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.RollMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What the attacker's and the target's conditions do to an attack roll between them now (PHB appendix A, p.290-292),
 * so the attack form can show it before the attack. The attacker's own choice of advantage or disadvantage, and its
 * other sources such as armour worn without proficiency, combine with these when it attacks (PHB p.173).
 *
 * @param rollMode       what these sources alone come to: advantage and disadvantage cancel
 * @param sources        each source with its reason, such as advantage for "target restrained"; empty for none
 * @param hitIsCritical  whether a hit is a critical hit: the target is unconscious or paralyzed and the attacker within
 *                       5 feet (PHB p.292)
 */
public record AttackRollPreview(
        @Schema(requiredMode = REQUIRED) RollMode rollMode,
        @Schema(requiredMode = REQUIRED) List<RollSource> sources,
        @Schema(requiredMode = REQUIRED) boolean hitIsCritical
) {

    public AttackRollPreview {
        sources = sources != null ? List.copyOf(sources) : List.of();
    }

    /**
     * One source of advantage or disadvantage.
     *
     * @param mode   ADVANTAGE or DISADVANTAGE
     * @param reason why, such as "target restrained" or "attacker poisoned"
     */
    public record RollSource(
            @Schema(requiredMode = REQUIRED) RollMode mode,
            @Schema(requiredMode = REQUIRED) String reason
    ) {
    }
}
