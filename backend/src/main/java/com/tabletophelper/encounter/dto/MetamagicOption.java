package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.Metamagic;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A Metamagic option the caster knows, as it can change one spell (PHB p.102, M18).
 *
 * @param option           the option
 * @param name             its PHB name, such as "Twinned Spell"
 * @param cost             the sorcery points it costs with the spell cast at its own level
 * @param costIsCastLevel  true for Twinned Spell, which costs the level the spell is cast at, 1 for a cantrip
 * @param maxCastLevel     the highest level it can change the spell at, when lower than 9th, as for Twinned Spell on a
 *                         spell that gains targets at higher levels; null when the level doesn't matter
 * @param ineligibleReason why it can't change the spell, or null when it can
 */
public record MetamagicOption(
        @Schema(requiredMode = REQUIRED) Metamagic.Option option,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) int cost,
        @Schema(requiredMode = REQUIRED) boolean costIsCastLevel,
        Integer maxCastLevel,
        String ineligibleReason
) {
}
