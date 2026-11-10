package com.tabletophelper.encounter.state;

import com.tabletophelper.rules.Ability;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The saving throw a creature makes each time it takes damage to end a condition and its spell, as Dominate Beast's
 * Wisdom save (PHB p.234) or Tasha's Hideous Laughter's, made with advantage (PHB p.280), stored on the condition's
 * entry (D185).
 *
 * @param ability   the save's ability
 * @param dc        the save's DC
 * @param advantage whether the save is made with advantage
 */
public record DamageSave(
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) boolean advantage
) {
}
