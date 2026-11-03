package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.DamageRules;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A DM Adjustment (D158): a named change the DM of a character's campaign makes on top of what the rules give the
 * character, in {@code player_characters.dm_adjustments}, oldest first (V22). Only the active ones count. A removed
 * one stays, with who removed it and when, as the record, and the character's player sees each one and its reason.
 * A field its kind doesn't use is left out.
 *
 * @param kind           what it changes
 * @param target         for a STAT, the stat, as one of {@link Stat} names it; for a DEFENCE, the damage type, in
 *                       lower case; for a FEAT, the feat's name
 * @param amount         for a STAT, what it adds: a whole number, negative to take away, never 0
 * @param defence        for a DEFENCE, the defence it gives against the damage type (D161)
 * @param featId         for a FEAT, the feat granted
 * @param appliedEffects for a FEAT, what granting the feat changed on the sheet, which removing it reverses exactly
 * @param reason         why, as the DM put it, such as "Ring of Protection"
 * @param addedBy        the DM who added it
 * @param removedBy      the DM who removed it; null while it is active
 * @param removedAt      when it was removed; null while it is active
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DmAdjustment(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED) Kind kind,
        @Schema(requiredMode = REQUIRED) String target,
        Integer amount,
        DamageRules.Defence defence,
        UUID featId,
        AppliedFeatEffects appliedEffects,
        @Schema(requiredMode = REQUIRED) String reason,
        @Schema(requiredMode = REQUIRED) UUID addedBy,
        @Schema(requiredMode = REQUIRED) Instant addedAt,
        UUID removedBy,
        Instant removedAt
) {

    /** What an adjustment changes. */
    public enum Kind {
        /** A Derived Stat or an ability score, by a signed amount */
        STAT,
        /** A damage resistance, immunity or vulnerability (D161) */
        DEFENCE,
        /** A feat granted outside an Ability Score Improvement */
        FEAT
    }

    /**
     * The stats a STAT adjustment can change: the armour class, hit point maximum, speed, initiative, spell save DC and
     * spell attack bonus, and the six ability scores. Saving throws and skills follow from the ability scores.
     */
    public enum Stat {
        ARMOUR_CLASS, HP_MAX, SPEED, INITIATIVE, SPELL_SAVE_DC, SPELL_ATTACK_BONUS, STR, DEX, CON, INT, WIS, CHA;

        /** The stat of an ability score. */
        public static Stat of(Ability ability) {
            return valueOf(ability.name());
        }

        /** The stat a target names, in any case; empty for none. */
        public static Optional<Stat> parse(String target) {
            if (target == null) return Optional.empty();
            String trimmed = target.trim();
            return Arrays.stream(values()).filter(stat -> stat.name().equalsIgnoreCase(trimmed)).findFirst();
        }
    }

    /** Whether it counts: it hasn't been removed. */
    public boolean active() {
        return removedAt == null;
    }

    /** The stat a STAT adjustment changes; empty for another kind. */
    public Optional<Stat> stat() {
        return kind == Kind.STAT ? Stat.parse(target) : Optional.empty();
    }

    /** The adjustment as the DM removed it at that time, kept as the record. */
    public DmAdjustment removed(UUID dmId, Instant at) {
        return new DmAdjustment(id, kind, target, amount, defence, featId, appliedEffects, reason, addedBy, addedAt,
                dmId, at);
    }
}
