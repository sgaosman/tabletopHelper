package com.tabletophelper.encounter.state;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The concentration check a creature made against an attack's damage (PHB p.203), kept on the hit ({@link WeaponHit})
 * so that damage added to the same hit afterwards, as Divine Smite's is (PHB p.85), is judged by this one saving throw
 * against the hit's total damage instead of a second save.
 *
 * @param instanceId the Concentration Instance it tested
 * @param damage     the damage it was made against, after the creature's defences
 * @param dc         its DC: 10 or half the damage, whichever is higher
 * @param total      the saving throw's total, as rolled
 * @param success    whether the total reached the DC
 */
public record ConcentrationCheck(
        @Schema(requiredMode = REQUIRED) UUID instanceId,
        @Schema(requiredMode = REQUIRED) int damage,
        @Schema(requiredMode = REQUIRED) int dc,
        @Schema(requiredMode = REQUIRED) int total,
        @Schema(requiredMode = REQUIRED) boolean success) {

    /** A concentration check's DC: 10 or half the damage taken, whichever is higher (PHB p.203). */
    public static int dcFor(int damage) {
        return Math.max(10, damage / 2);
    }
}
