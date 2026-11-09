package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.Ability;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A weapon a player character can attack with, with the numbers the server works out for it (D164): a weapon from
 * their inventory, or an unarmed strike. The numbers are those of the weapon's usual grip (two hands for a two-handed
 * weapon, one otherwise) with the character's class features and Fighting Styles (M17).
 *
 * @param weapon          what an attack names to use it, such as "Longsword"
 * @param ability         the ability it attacks with: STR, DEX, or the higher for a finesse weapon or Martial Arts
 * @param proficient      whether the attack bonus includes the proficiency bonus
 * @param attackBonus     the attack bonus, with the attack roll bonuses in force now: Sacred Weapon (PHB p.86) and
 *                        War God's Blessing (PHB p.63), each also named in {@code features}
 * @param damage          the damage dice and modifier, such as "1d8 + 3"; a critical hit doubles the dice
 * @param ranged          a ranged weapon, which attacks from beyond 5 feet unless the attacker says otherwise
 * @param powerAttack     Great Weapon Master's or Sharpshooter's trade, when the character may take it with this weapon
 * @param recklessAttack  whether the character may attack recklessly with it: it has Reckless Attack and the weapon
 *                        attacks in melee with Strength (PHB p.48)
 * @param twoHanded       a two-handed weapon, which needs both hands (PHB p.147)
 * @param versatileDamage a versatile weapon's damage with two hands, with the character's features, such as
 *                        "1d10 + 3"; null for a weapon that isn't versatile
 * @param features        the class features and Fighting Styles in its numbers, such as "Archery +2" or "Martial Arts"
 * @param critRange       the lowest d20 that is a critical hit with it: 20, or 19 and 18 for a Champion (PHB p.72-73)
 * @param sneakAttack     Sneak Attack's dice when the character is a Rogue and the weapon is finesse or ranged, such as
 *                        "2d6" (PHB p.96); null otherwise
 * @param martialArts     whether Martial Arts applies to it, so the Attack action with it lets a monk make an unarmed
 *                        strike as a bonus action (PHB p.78)
 */
public record AttackOption(
        @Schema(requiredMode = REQUIRED) String weapon,
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) boolean proficient,
        @Schema(requiredMode = REQUIRED) int attackBonus,
        @Schema(requiredMode = REQUIRED) String damage,
        String damageType,
        @Schema(requiredMode = REQUIRED) boolean ranged,
        PowerAttackOption powerAttack,
        @Schema(requiredMode = REQUIRED) boolean recklessAttack,
        @Schema(requiredMode = REQUIRED) boolean twoHanded,
        String versatileDamage,
        @Schema(requiredMode = REQUIRED) List<String> features,
        @Schema(requiredMode = REQUIRED) int critRange,
        String sneakAttack,
        @Schema(requiredMode = REQUIRED) boolean martialArts
) {

    public AttackOption {
        features = features != null ? List.copyOf(features) : List.of();
    }

    /** A weapon with none of the class features of M17. */
    public AttackOption(String weapon, Ability ability, boolean proficient, int attackBonus, String damage,
                        String damageType, boolean ranged, PowerAttackOption powerAttack, boolean recklessAttack) {
        this(weapon, ability, proficient, attackBonus, damage, damageType, ranged, powerAttack, recklessAttack, false,
                null, List.of(), 20, null, false);
    }

    /** A weapon the character can't attack recklessly with. */
    public AttackOption(String weapon, Ability ability, boolean proficient, int attackBonus, String damage,
                        String damageType, boolean ranged, PowerAttackOption powerAttack) {
        this(weapon, ability, proficient, attackBonus, damage, damageType, ranged, powerAttack, false);
    }

    /**
     * The attack with the feat's trade taken: 5 off the attack roll for 10 more damage.
     *
     * @param feat "Great Weapon Master" or "Sharpshooter"
     */
    public record PowerAttackOption(
            @Schema(requiredMode = REQUIRED) String feat,
            @Schema(requiredMode = REQUIRED) int attackBonus,
            @Schema(requiredMode = REQUIRED) String damage
    ) {
    }
}
