package com.tabletophelper.character.dto;

/**
 * A single resource pool entry stored on player_characters.resource_pools
 * and encounter_participants.resource_pools_current.
 *
 * <p>Subsumes the old {@code feat_resources} and {@code hit_dice_map} patterns.
 * Spell slots, HP, death saves, conditions, and action economy remain separate.</p>
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
 * @param metadata         extra JSONB data (hitDie faces, breath shape, etc.)
 */
public record ResourcePoolEntry(
        String poolId,
        String displayName,
        String sourceType,
        String sourceName,
        int maxUses,
        String maxUsesFormula,
        int currentUses,
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
}
