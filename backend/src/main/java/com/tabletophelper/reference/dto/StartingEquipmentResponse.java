package com.tabletophelper.reference.dto;

import com.tabletophelper.reference.Item;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * A class's or background's starting equipment (PHB p.143), read from its 5etools data, with each item linked to the
 * items table where one matches, for the creation wizard to offer.
 *
 * @param groups          the lines of the list in order: each either always given or a choice of (a), (b), (c)...
 * @param goldAlternative the class's starting wealth taken in place of the equipment, or null (a background has none)
 */
public record StartingEquipmentResponse(
        @Schema(requiredMode = REQUIRED) List<EquipmentGroup> groups,
        StartingGold goldAlternative) {

    /**
     * One line of the list.
     *
     * @param choice  whether the player picks one of the options; when false there is one option, always given
     * @param options the options, keyed "a", "b", "c" and so on, or one keyed "_" when nothing is chosen
     */
    public record EquipmentGroup(
            @Schema(requiredMode = REQUIRED) boolean choice,
            @Schema(requiredMode = REQUIRED) List<EquipmentOption> options) {}

    /** One option of a line, such as "(b) leather armor, longbow, and 20 arrows". */
    public record EquipmentOption(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) List<EquipmentEntry> entries) {}

    /** What an entry is: a thing carried, coins, or a thing the player picks from a list, such as a martial weapon. */
    public enum EquipmentKind { ITEM, COINS, PICK }

    /**
     * One entry of an option.
     *
     * @param name           what the sheet calls it, such as "Chain Mail" or "Insignia of rank"; for COINS the amount,
     *                       such as "10 gp", and for PICK what is picked, such as "Martial weapon"
     * @param quantity       how many; for PICK how many to pick, each picked separately
     * @param itemId         the item it is, or null when no item matches (an item group such as a holy symbol, or
     *                       something only described, such as a trophy)
     * @param armourCategory the item's armour category when it is armour or a shield, otherwise null
     * @param coinsCp        coins that come with it, in copper pieces: a COINS entry's amount, or what a pouch
     *                       contains; 0 for none
     * @param candidates     for PICK, the items to pick from; empty otherwise
     */
    public record EquipmentEntry(
            @Schema(requiredMode = REQUIRED) EquipmentKind kind,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) int quantity,
            UUID itemId,
            Item.ArmourCategory armourCategory,
            @Schema(requiredMode = REQUIRED) int coinsCp,
            @Schema(requiredMode = REQUIRED) List<EquipmentCandidate> candidates) {}

    /** An item a PICK entry offers. */
    public record EquipmentCandidate(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) UUID itemId) {}

    /**
     * The starting wealth (PHB p.143): {@code count}d{@code sides} × {@code multiplier} gold pieces, such as 5d4 × 10.
     *
     * @param multiplier 1 for the Monk's 5d4
     */
    public record StartingGold(
            @Schema(requiredMode = REQUIRED) int count,
            @Schema(requiredMode = REQUIRED) int sides,
            @Schema(requiredMode = REQUIRED) int multiplier) {}
}
