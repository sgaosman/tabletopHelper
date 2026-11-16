package com.tabletophelper.character.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Damage, healing or temporary hit points on a character's sheet between encounters (D158): damage comes off the
 * temporary hit points first and stops at 0, healing stops at the maximum, and temporary hit points keep the larger of
 * the old and new amounts. Combat changes a participant's hit points instead. At most 1,000 at once, well past any
 * character's hit points, so no amount overflows the sums (D221).
 */
public record HitPointChangeRequest(
        @NotNull Kind kind,
        @NotNull @Min(1) @Max(1000) Integer amount
) {

    public enum Kind { DAMAGE, HEALING, TEMPORARY }
}
