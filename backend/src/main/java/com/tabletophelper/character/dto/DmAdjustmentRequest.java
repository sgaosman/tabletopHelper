package com.tabletophelper.character.dto;

import com.tabletophelper.character.state.DmAdjustment;
import com.tabletophelper.rules.combat.DamageRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A DM Adjustment the DM of a character's campaign adds (D158). What it needs depends on its kind, and a field another
 * kind uses is refused:
 * <ul>
 *   <li>STAT: the stat as the target, one of {@link DmAdjustment.Stat}'s names, and the amount it adds, negative to
 *       take away, never 0</li>
 *   <li>DEFENCE: the defence, and a damage type combat knows as the target, such as "fire" (D161)</li>
 *   <li>FEAT: the feat, with the choices it takes, as an Ability Score Improvement's feat has them: the ability a
 *       half-feat raises, or the spells Fey Touched or Magic Initiate gives, for example</li>
 * </ul>
 *
 * @param reason why, such as "Ring of Protection", which the character's player sees
 */
public record DmAdjustmentRequest(
        @NotNull DmAdjustment.Kind kind,
        String target,
        @Min(-100) @Max(100) Integer amount,
        DamageRules.Defence defence,
        ApplyChoicesRequest.AsiChoice feat,
        @NotBlank @Size(max = 200) String reason
) {
}
