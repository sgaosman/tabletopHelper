package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A participant's latest melee weapon hit, kept in its turn economy (M17) for what a hit lets it do afterwards: Divine
 * Smite, which the paladin chooses once it knows it hit and whether critically (PHB p.85), and Stunning Strike (PHB
 * p.79). Each may follow it once, and only on the turn it was made. The participant's next attack replaces it, and the
 * start of its own turn clears it.
 *
 * @param turn                  the turn of the encounter it was made on, as EncounterTurns' stamp names it
 * @param attackLogId           the attack's combat log entry; null only where the log gives entries no ID
 * @param targetId              the creature hit
 * @param weapon                the weapon's name, or "Unarmed Strike"
 * @param critical              it was a critical hit, which doubles a smite's dice (PHB p.196)
 * @param divineSmiteOffered    the attacker may follow it with Divine Smite: a Paladin of 2nd level or more, after any
 *                              melee weapon attack, an unarmed strike's included (PHB p.85, p.195)
 * @param stunningStrikeOffered the attacker may follow it with Stunning Strike: a Monk of 5th level or more (PHB p.79)
 * @param smitten               Divine Smite has followed it
 * @param stunningStrikeUsed    Stunning Strike has followed it
 * @param concentrationCheck    the concentration check the hit's damage made the creature hit roll, if it made one; damage
 *                              added to the hit later, as Divine Smite's, is judged by this same save (PHB p.203)
 */
public record WeaponHit(
        @Schema(requiredMode = REQUIRED) String turn,
        UUID attackLogId,
        @Schema(requiredMode = REQUIRED) UUID targetId,
        @Schema(requiredMode = REQUIRED) String weapon,
        @Schema(requiredMode = REQUIRED) boolean critical,
        @Schema(requiredMode = REQUIRED) boolean divineSmiteOffered,
        @Schema(requiredMode = REQUIRED) boolean stunningStrikeOffered,
        @Schema(requiredMode = REQUIRED) boolean smitten,
        @Schema(requiredMode = REQUIRED) boolean stunningStrikeUsed,
        @JsonInclude(JsonInclude.Include.NON_NULL) ConcentrationCheck concentrationCheck) {

    /** A hit nothing has followed yet. */
    public static WeaponHit of(String turn, UUID attackLogId, UUID targetId, String weapon, boolean critical,
                               boolean divineSmiteOffered, boolean stunningStrikeOffered) {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                false, false, null);
    }

    /** The same hit, with the concentration check its damage made the creature hit roll, or null for none. */
    public WeaponHit withConcentrationCheck(ConcentrationCheck check) {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                smitten, stunningStrikeUsed, check);
    }

    /** The same hit, once Divine Smite has followed it. */
    public WeaponHit smite() {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                true, stunningStrikeUsed, concentrationCheck);
    }

    /** The same hit, once Stunning Strike has followed it. */
    public WeaponHit stunningStrike() {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                smitten, true, concentrationCheck);
    }
}
