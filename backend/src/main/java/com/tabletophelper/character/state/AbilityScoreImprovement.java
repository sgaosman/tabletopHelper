package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * An Ability Score Improvement as a level history entry records it (D168): ability score increases, or a feat
 * taken instead. Levelling down reverses it. A field it doesn't have is left out.
 *
 * @param type           "ability" or "feat"
 * @param increases      for "ability", each ability and the bonus requested
 * @param featName       the feat taken
 * @param featId         the feat taken, for a feat whose effects were applied
 * @param featAbility    the ability a feat named only by its name increased by 1
 * @param appliedEffects what applying the feat changed, which levelling down reverses
 * @param applied        each increase as actually applied under the ability score maximum (D167), for the increases or
 *                       the feat ability; an improvement recorded before R6 has none, and levelling down then takes
 *                       off the amount requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AbilityScoreImprovement(
        @Schema(allowableValues = {"ability", "feat"}) String type,
        List<AbilityBonus> increases,
        String featName,
        UUID featId,
        String featAbility,
        AppliedFeatEffects appliedEffects,
        List<AbilityBonus> applied
) {

    public AbilityScoreImprovement withFeatId(UUID newFeatId) {
        return new AbilityScoreImprovement(type, increases, featName, newFeatId, featAbility, appliedEffects, applied);
    }

    public AbilityScoreImprovement withAppliedEffects(AppliedFeatEffects newAppliedEffects) {
        return new AbilityScoreImprovement(type, increases, featName, featId, featAbility, newAppliedEffects, applied);
    }
}
