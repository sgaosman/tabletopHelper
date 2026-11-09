package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.combat.AttackFeatures;
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

    // What the attack costs from the attacker's turn (D174): an attack of the Attack action when left out, an off-hand
    // attack as a bonus action, or an opportunity attack as a reaction, which may be made on another creature's turn;
    // or a monk's bonus-action unarmed strike from Martial Arts or Flurry of Blows (PHB p.78, M17)
    private AttackCost cost;

    // Reckless Attack (PHB p.48): a barbarian with the feature chooses it on the first attack of its turn, a melee weapon
    // attack using Strength, for advantage on those attacks this turn and advantage for attacks against it until its
    // next turn. Once chosen, the turn's other melee Strength attacks have advantage without it
    private Boolean recklessAttack;

    // How the attacker holds its weapons (M17): one-handed, two-handed (a two-handed weapon always, and a versatile one
    // for its larger damage, PHB p.147) or a weapon in each hand. Left out: two-handed for a two-handed weapon, a weapon
    // in each hand for an off-hand attack, one-handed otherwise. Dueling needs one-handed and Great Weapon Fighting
    // two-handed (PHB p.72)
    private AttackFeatures.Grip grip;

    // Sneak Attack (PHB p.96), once per turn with a finesse or ranged weapon. Left out, it comes with a hit that had
    // advantage; true claims it without advantage, when another enemy of the target is within 5 feet of it and isn't
    // incapacitated, which the server can't see; false declines it. Never with disadvantage
    private Boolean sneakAttack;

    // Whether the attacker is within 5 feet of the target, which makes a hit on an unconscious or paralyzed
    // target a critical hit (D161). There is no map, so leaving it out counts as true, except for a ranged weapon.
    private Boolean within5Ft;
}
