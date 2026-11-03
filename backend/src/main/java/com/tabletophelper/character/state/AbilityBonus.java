package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * An increase to one ability score (D168): a racial bonus in {@code player_characters.racial_ability_bonuses},
 * where the ability is abbreviated ("DEX") and is null until the player assigns it, or an increase the level history
 * records, from an Ability Score Improvement or from the level itself, where it is spelled out ("dexterity").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AbilityBonus(
        String ability,
        @Schema(requiredMode = REQUIRED) int bonus
) {
}
