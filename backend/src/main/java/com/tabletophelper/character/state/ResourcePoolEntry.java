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
 * @param resetOn          shortRest, longRest, turn, dawn, never, or none for a pool no rest restores (D210)
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

    /** The same pool, named as coming from this source, such as a race's pool from "Half-Orc" (D208). */
    public ResourcePoolEntry withSourceName(String newSourceName) {
        return new ResourcePoolEntry(
                poolId, displayName, sourceType, newSourceName,
                maxUses, maxUsesFormula, currentUses,
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
     * The pool that holds a warlock's Mystic Arcanum of one spell level, on a character sheet or a participant:
     * "mystic-arcanum:" and the level, such as "mystic-arcanum:6" (PHB p.108).
     */
    public static String mysticArcanumPoolId(int spellLevel) {
        return "mystic-arcanum:" + spellLevel;
    }

    /**
     * A full pool of a Mystic Arcanum's one use, which comes back on a long rest (PHB p.108): "Mystic Arcanum (6th)",
     * with the spell and its level in {@code metadata.spell} and {@code metadata.spellLevel}.
     */
    public static ResourcePoolEntry mysticArcanum(int spellLevel, String spellName) {
        return new ResourcePoolEntry(mysticArcanumPoolId(spellLevel), "Mystic Arcanum (" + ordinal(spellLevel) + ")",
                "CLASS", "Warlock", 1, null, 1, "longRest", null, null, "ACTION", "sparkles",
                Map.of("spell", spellName, "spellLevel", spellLevel));
    }

    // "6th", "7th", "8th" or "9th", as a spell's level is written
    private static String ordinal(int level) {
        return switch (level) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> level + "th";
        };
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

    /**
     * The pool that holds a magic item's charges, on a character sheet or a participant: "item:" and the item's name in
     * kebab case, such as "item:wand-of-magic-missiles" (D210).
     */
    public static String itemPoolId(String itemName) {
        return "item:" + kebabCase(itemName);
    }

    /** The resetOn of a pool that no rest restores, as a magic item's that never regains its charges (D210). */
    public static final String NO_RESET = "none";

    // When an item regains its charges in the data: at dawn, dusk or midnight, or on a long rest
    private static final java.util.Set<String> DAILY = java.util.Set.of("dawn", "dusk", "midnight", "restLong");

    /**
     * A full pool of a magic item's charges (D210), spent with an action. One that regains them daily, at dawn, dusk or
     * midnight, gets them back on a long rest, the app's nearest to a day passing: all of them, or currentUses plus the
     * data's dice, as a Wand of Magic Missiles' 1d6 + 1. One that never does, or does by its own rule ("special"), keeps
     * them through every rest, the DM restoring them.
     *
     * @param recharge       when the item regains its charges, as the items table has it; null for never, unless it
     *                       has a rechargeAmount: the data then leaves the time out, as the Robe of Stars' stars that
     *                       reappear "daily at dusk", which counts as daily
     * @param rechargeAmount how many it regains, such as "1d6 + 1"; null for all of them
     */
    public static ResourcePoolEntry magicItem(String itemName, int charges, String recharge, String rechargeAmount) {
        boolean hasAmount = rechargeAmount != null && !rechargeAmount.isBlank();
        boolean daily = recharge != null ? DAILY.contains(recharge) : hasAmount;
        String resetAmount = daily && rechargeAmount != null && !rechargeAmount.isBlank()
                ? "currentUses + (" + rechargeAmount.trim() + ")" : null;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (recharge != null) metadata.put("recharge", recharge);
        return new ResourcePoolEntry(itemPoolId(itemName), itemName, "MAGIC_ITEM", itemName, charges, null, charges,
                daily ? "longRest" : NO_RESET, resetAmount, null, "ACTION", "wand", metadata);
    }

    private static String kebabCase(String name) {
        return name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
