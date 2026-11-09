package com.tabletophelper.character.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Locale;
import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A single resource pool entry stored on player_characters.resource_pools
 * and encounter_participants.resource_pools_current, where it is typed (D168).
 *
 * <p>It replaced the old {@code feat_resources} column: a feat's limited uses are a {@code feat:<feat>} pool
 * (D158). Hit dice are not a pool: they have their own {@code hit_dice} column ({@link HitDicePool}). Spell slots,
 * HP, death saves, conditions, and action economy remain separate.</p>
 *
 * @param poolId           canonical identifier, e.g. "class:monk-ki"
 * @param displayName      human-readable label
 * @param sourceType       CLASS, RACE, FEAT, MONSTER, MAGIC_ITEM, BACKGROUND
 * @param sourceName       name of the source entity, e.g. "Monk"
 * @param maxUses          evaluated integer max (evaluated from maxUsesFormula at creation)
 * @param maxUsesFormula   raw expression string for reference
 * @param currentUses      mutable remaining uses
 * @param resetOn          shortRest, longRest, turn, dawn, never
 * @param resetAmount      expression for partial recovery; null = full reset
 * @param resetCheck       probabilistic recharge expression, e.g. "1d6>=5"
 * @param spendActionType  ACTION, BONUS_ACTION, REACTION, FREE, REPLACE_ATTACK
 * @param icon             lucide-react icon name
 * @param metadata         extra JSONB data: {@code die}, the pool's die from its level table, such as "d8";
 *                         {@code unlimited}, true for a pool with no limit, such as Rage at Barbarian 20, whose
 *                         spending is never refused; {@code recovers}, the pool a use of this one gives a use back
 *                         to, such as a psionic recovery's Psionic Energy dice (D169); {@code effect}, what a use
 *                         does in an encounter beyond spending, such as "rage" or "second-wind" (M17)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourcePoolEntry(
        @Schema(requiredMode = REQUIRED) String poolId,
        @Schema(requiredMode = REQUIRED) String displayName,
        @Schema(requiredMode = REQUIRED) String sourceType,
        String sourceName,
        @Schema(requiredMode = REQUIRED) int maxUses,
        String maxUsesFormula,
        @Schema(requiredMode = REQUIRED) int currentUses,
        String resetOn,
        String resetAmount,
        String resetCheck,
        String spendActionType,
        String icon,
        Object metadata
) {
    /** Convenience constructor with defaults for Java-internal creation. */
    public ResourcePoolEntry withCurrentUses(int newCurrentUses) {
        return new ResourcePoolEntry(
                poolId, displayName, sourceType, sourceName,
                maxUses, maxUsesFormula, newCurrentUses,
                resetOn, resetAmount, resetCheck,
                spendActionType, icon, metadata);
    }

    /** Whether the pool has no limit, such as Rage at Barbarian 20: spending it is never refused (D169). */
    @JsonIgnore
    public boolean unlimited() {
        return metadata instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("unlimited"));
    }

    /** The pool a use of this one gives a use back to, such as the Psionic Energy dice, or null (D169). */
    @JsonIgnore
    public String recovers() {
        return metadata instanceof Map<?, ?> map && map.get("recovers") instanceof String poolId ? poolId : null;
    }

    /**
     * The pool that holds a spell's innate uses, on a character sheet or a participant: "innate:" and the spell's name
     * in kebab case (D165).
     */
    public static String innatePoolId(String spellName) {
        return "innate:" + kebabCase(spellName);
    }

    /** A full pool of a spell's innate uses, which come back on a long rest (D165). */
    public static ResourcePoolEntry innate(String spellName, int uses, String sourceType, String sourceName) {
        return new ResourcePoolEntry(innatePoolId(spellName), spellName, sourceType, sourceName, uses, null, uses,
                "longRest", null, null, "ACTION", "sparkles", Map.of());
    }

    /**
     * The pool that holds a feat's limited uses, such as Lucky's luck points: "feat:" and the feat's name in kebab
     * case (D158).
     */
    public static String featPoolId(String featName) {
        return "feat:" + kebabCase(featName);
    }

    /**
     * A full pool of a feat's limited uses, which come back on the rest the feat names. Spending one takes no action:
     * the feat says when it applies.
     *
     * @param displayName what the uses are called, such as "Luck Points"
     */
    public static ResourcePoolEntry feat(String featName, String displayName, int uses, String resetOn) {
        return new ResourcePoolEntry(featPoolId(featName), displayName, "FEAT", featName, uses, null, uses,
                resetOn, null, null, "FREE", "sparkles", Map.of());
    }

    private static String kebabCase(String name) {
        return name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
