package com.tabletophelper.encounter.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * An attack, one of two kinds (D164). A weapon attack names a weapon from the attack options, and the server works
 * out its attack bonus and damage; it may add Manual Modifiers: an extra attack bonus, extra damage and a forced
 * critical hit. An attack entered by hand gives attackBonus and damageDice instead, and is itself a Manual Modifier.
 * The DM may always enter Manual Modifiers, and a player while the campaign allows them.
 */
@Data
public class AttackRollRequest {

    @NotNull
    private UUID targetId;

    // A weapon the attacker's attack options list, such as "Longsword" or "Unarmed Strike"
    private String weapon;

    // Great Weapon Master's or Sharpshooter's trade, 5 off the attack roll for 10 more damage, which the weapon's
    // attack option must offer
    private Boolean powerAttack;

    // Manual Modifiers added to a weapon attack's own numbers, such as a magic weapon's +1; the extra damage is the
    // weapon's damage type unless another is given
    private Integer extraAttackBonus;

    private String extraDamageDice;

    private String extraDamageType;

    // An attack entered by hand, in place of a weapon
    private Integer attackBonus;

    private String damageDice;

    private String damageType;

    private Boolean advantage;

    // A Manual Modifier: the attack is a critical hit whatever the roll (D161)
    private Boolean forceCrit;

    // Whether the attacker is within 5 feet of the target, which makes a hit on an unconscious or paralyzed
    // target a critical hit (D161). There is no map, so leaving it out counts as true, except for a ranged weapon.
    private Boolean within5Ft;
}
