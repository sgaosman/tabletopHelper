package com.tabletophelper.encounter.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A participant's latest melee weapon hit, kept in its turn economy (M17) for what a hit lets it do afterwards: Divine
 * Smite, which the paladin chooses once it knows it hit and whether critically (PHB p.85), Stunning Strike (PHB
 * p.79), and a Kensei monk's Deft Strike, which follows a ranged kensei weapon's hit too (XGE p.34). Each may follow it once, and only on the turn it was made. The participant's next attack replaces it, and the
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
 * @param ranged                it was a ranged attack, which Divine Smite and Stunning Strike can't follow: a Kensei
 *                              monk's hit with a ranged kensei weapon is kept for Deft Strike alone (XGE p.34). Written
 *                              only when true
 * @param deftStrikeOffered     the attacker may follow it with Deft Strike: a Way of the Kensei monk of 6th level or
 *                              more, after a hit with a kensei weapon on its own turn (XGE p.34). Written only when true
 * @param deftStrikeUsed        Deft Strike has followed it. Written only when true
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
        @JsonInclude(JsonInclude.Include.NON_NULL) ConcentrationCheck concentrationCheck,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean ranged,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean deftStrikeOffered,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean deftStrikeUsed) {

    /** A melee hit that Deft Strike doesn't follow, as recorded before Deft Strike was. */
    public WeaponHit(String turn, UUID attackLogId, UUID targetId, String weapon, boolean critical,
                     boolean divineSmiteOffered, boolean stunningStrikeOffered, boolean smitten,
                     boolean stunningStrikeUsed, ConcentrationCheck concentrationCheck) {
        this(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered, smitten,
                stunningStrikeUsed, concentrationCheck, false, false, false);
    }

    /** A melee hit nothing has followed yet. */
    public static WeaponHit of(String turn, UUID attackLogId, UUID targetId, String weapon, boolean critical,
                               boolean divineSmiteOffered, boolean stunningStrikeOffered) {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                false, false, null);
    }

    /**
     * A hit nothing has followed yet: Divine Smite and Stunning Strike are offered only after a melee one (PHB p.85,
     * p.79), Deft Strike after any with a kensei weapon (XGE p.34).
     */
    public static WeaponHit of(String turn, UUID attackLogId, UUID targetId, String weapon, boolean critical,
                               boolean ranged, boolean divineSmiteOffered, boolean stunningStrikeOffered,
                               boolean deftStrikeOffered) {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, !ranged && divineSmiteOffered,
                !ranged && stunningStrikeOffered, false, false, null, ranged, deftStrikeOffered, false);
    }

    /** The same hit, as made on the turn named, as the Player View masks a hidden participant's turn (D157). */
    public WeaponHit withTurn(String newTurn) {
        return new WeaponHit(newTurn, attackLogId, targetId, weapon, critical, divineSmiteOffered,
                stunningStrikeOffered, smitten, stunningStrikeUsed, concentrationCheck, ranged, deftStrikeOffered,
                deftStrikeUsed);
    }

    /** The same hit, with the concentration check its damage made the creature hit roll, or null for none. */
    public WeaponHit withConcentrationCheck(ConcentrationCheck check) {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                smitten, stunningStrikeUsed, check, ranged, deftStrikeOffered, deftStrikeUsed);
    }

    /** The same hit, once Divine Smite has followed it. */
    public WeaponHit smite() {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                true, stunningStrikeUsed, concentrationCheck, ranged, deftStrikeOffered, deftStrikeUsed);
    }

    /** The same hit, once Stunning Strike has followed it. */
    public WeaponHit stunningStrike() {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                smitten, true, concentrationCheck, ranged, deftStrikeOffered, deftStrikeUsed);
    }

    /** The same hit, once Deft Strike has followed it. */
    public WeaponHit deftStrike() {
        return new WeaponHit(turn, attackLogId, targetId, weapon, critical, divineSmiteOffered, stunningStrikeOffered,
                smitten, stunningStrikeUsed, concentrationCheck, ranged, deftStrikeOffered, true);
    }
}
