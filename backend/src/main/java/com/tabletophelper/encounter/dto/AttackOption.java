package com.tabletophelper.encounter.dto;

import com.tabletophelper.rules.Ability;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A weapon a player character can attack with, with the numbers the server works out for it (D164): a weapon from
 * their inventory, or an unarmed strike.
 *
 * @param weapon      what an attack names to use it, such as "Longsword"
 * @param ability     the ability it attacks with: STR, DEX, or the higher for a finesse weapon
 * @param proficient  whether the attack bonus includes the proficiency bonus
 * @param attackBonus the attack bonus
 * @param damage      the damage dice and modifier, such as "1d8 + 3"; a critical hit doubles the dice
 * @param ranged      a ranged weapon, which attacks from beyond 5 feet unless the attacker says otherwise
 * @param powerAttack Great Weapon Master's or Sharpshooter's trade, when the character may take it with this weapon
 */
public record AttackOption(
        @Schema(requiredMode = REQUIRED) String weapon,
        @Schema(requiredMode = REQUIRED) Ability ability,
        @Schema(requiredMode = REQUIRED) boolean proficient,
        @Schema(requiredMode = REQUIRED) int attackBonus,
        @Schema(requiredMode = REQUIRED) String damage,
        String damageType,
        @Schema(requiredMode = REQUIRED) boolean ranged,
        PowerAttackOption powerAttack
) {

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
