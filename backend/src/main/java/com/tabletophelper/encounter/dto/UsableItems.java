package com.tabletophelper.encounter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What a player character participant can use from its inventory in combat (D210): its sheet's items less those it has
 * used up in the encounter. A monster has none.
 *
 * @param potions      the potions it carries, drunk or given with use-item
 * @param chargedItems the linked items whose charges cast spells, cast with cast-spell and the source ITEM
 * @param scrolls      the spell scrolls with a spell recorded, read with cast-spell and the source SCROLL
 */
public record UsableItems(
        @Schema(requiredMode = REQUIRED) List<Potion> potions,
        @Schema(requiredMode = REQUIRED) List<ChargedItem> chargedItems,
        @Schema(requiredMode = REQUIRED) List<Scroll> scrolls
) {

    /**
     * A potion.
     *
     * @param name        the inventory entry's name
     * @param left        how many it still carries
     * @param healing     the hit points a potion of healing restores, such as "2d4 + 2" (DMG p.187); null for any other
     *                    potion, whose effect the DM applies
     * @param description what the potion does, from its item
     */
    public record Potion(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) int left,
            String healing,
            String description
    ) {
    }

    /**
     * A magic item that casts spells with its charges.
     *
     * @param name              the inventory entry's name
     * @param chargesLeft       its charges in this encounter; 0 when it hasn't its pool
     * @param chargesMax        how many it holds
     * @param saveDc            the save DC its own text gives its spells, as a Wand of Fireballs' 15; null for the
     *                          wielder's
     * @param attackBonus       the spell attack bonus its own text gives; null for the wielder's
     * @param unavailableReason why its spells can't be cast now, such as needing attunement; null when they can
     * @param spells            its spells, each with ITEM as its one source and the charges each level costs
     */
    public record ChargedItem(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) int chargesLeft,
            @Schema(requiredMode = REQUIRED) int chargesMax,
            Integer saveDc,
            Integer attackBonus,
            String unavailableReason,
            @Schema(requiredMode = REQUIRED) List<SpellOption> spells
    ) {
    }

    /**
     * A spell scroll (DMG p.200).
     *
     * @param name         the inventory entry's name
     * @param spell        its spell, with SCROLL as its one source at the scroll's level; unavailable when the spell is
     *                     on none of the reader's classes' spell lists
     * @param level        the scroll's level, 0 for a cantrip
     * @param saveDc       the save DC it casts with
     * @param attackBonus  the attack bonus it casts with
     * @param checkDc      the DC of the check reading it takes first, its spell being of a higher level than the reader
     *                     can cast; null for none
     * @param checkAbility the ability of that check; null for none
     */
    public record Scroll(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) SpellOption spell,
            @Schema(requiredMode = REQUIRED) int level,
            @Schema(requiredMode = REQUIRED) int saveDc,
            @Schema(requiredMode = REQUIRED) int attackBonus,
            Integer checkDc,
            String checkAbility
    ) {
    }
}
