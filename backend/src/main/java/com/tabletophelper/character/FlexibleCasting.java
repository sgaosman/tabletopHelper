package com.tabletophelper.character;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.character.FontOfMagic;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Font of Magic's Flexible Casting on a sheet's or a participant's slots and pools (PHB p.101, M18): one exchange
 * between a spell slot and sorcery points, worked out the same way on the character sheet and in an encounter, which
 * charges it a bonus action. Created slots are counted on their level's {@link SpellSlot#created()} and vanish on a
 * long rest. A creature without the Sorcery Points pool, which a sorcerer has from 2nd level, has no Font of Magic.
 */
public final class FlexibleCasting {

    /** The sorcerer's Sorcery Points pool, as resource-pools.json names it. */
    public static final String SORCERY_POINTS = "class:sorcerer-sorcery-points";

    private static final String PACT_SLOT = "pact_";

    /**
     * The slots and pools after one exchange, and what the log says of it, such as "converts a level 2 spell slot into
     * 2 sorcery points (5/5)".
     */
    public record Exchange(Map<String, SpellSlot> slots, List<ResourcePoolEntry> pools, String description) {
    }

    private FlexibleCasting() {
    }

    /**
     * One exchange. Expending a slot takes one of the level's slots, or a Warlock's pact slots of that level when it
     * has no others, and gains points equal to its level up to the pool's maximum; creating one spends the Creating
     * Spell Slots table's points and adds a created slot of the level, 1st to 5th.
     *
     * @param owner whose slots they are, for the messages
     * @throws ValidationException for a slot level Flexible Casting can't use
     * @throws ConflictException   for a creature without sorcery points, no slot of the level left, sorcery points
     *                             already at their maximum, or too few points
     */
    public static Exchange exchange(String owner, Map<String, SpellSlot> slots, List<ResourcePoolEntry> pools,
                                    FontOfMagic.Direction direction, int slotLevel) {
        Objects.requireNonNull(direction, "direction");
        ResourcePoolEntry points = sorceryPoints(pools)
                .orElseThrow(() -> new ConflictException(owner + " has no sorcery points (Font of Magic, Sorcerer 2)"));
        Map<String, SpellSlot> changed = new LinkedHashMap<>(slots != null ? slots : Map.of());
        return switch (direction) {
            case POINTS_TO_SLOT -> create(owner, changed, pools, points, slotLevel);
            case SLOT_TO_POINTS -> expend(owner, changed, pools, points, slotLevel);
        };
    }

    /** The Sorcery Points pool among these pools, if there is one. */
    public static Optional<ResourcePoolEntry> sorceryPoints(List<ResourcePoolEntry> pools) {
        return (pools != null ? pools : List.<ResourcePoolEntry>of()).stream()
                .filter(pool -> pool != null && SORCERY_POINTS.equals(pool.poolId()))
                .findFirst();
    }

    private static Exchange create(String owner, Map<String, SpellSlot> slots, List<ResourcePoolEntry> pools,
                                   ResourcePoolEntry points, int slotLevel) {
        if (!FontOfMagic.canCreate(slotLevel)) {
            throw new ValidationException("Flexible Casting creates spell slots of 1st to "
                    + FontOfMagic.MAX_CREATED_LEVEL + " level, not level " + slotLevel);
        }
        int cost = FontOfMagic.creationCost(slotLevel);
        if (points.currentUses() < cost) {
            throw new ConflictException(owner + " has " + points.currentUses() + " sorcery points, and a level "
                    + slotLevel + " spell slot costs " + cost);
        }
        List<ResourcePoolEntry> spent = ResourcePoolService.spend(pools, SORCERY_POINTS, cost, owner);
        String key = String.valueOf(slotLevel);
        SpellSlot slot = slots.get(key);
        SpellSlot created = (slot != null ? slot : new SpellSlot(0, 0)).create();
        slots.put(key, created);
        ResourcePoolEntry after = sorceryPoints(spent).orElseThrow();
        return new Exchange(slots, spent, "creates a level " + slotLevel + " spell slot from " + cost
                + " sorcery points (" + created.remaining() + "/" + created.total() + " level " + slotLevel
                + " slots; " + after.currentUses() + "/" + after.maxUses() + " sorcery points)");
    }

    private static Exchange expend(String owner, Map<String, SpellSlot> slots, List<ResourcePoolEntry> pools,
                                   ResourcePoolEntry points, int slotLevel) {
        if (slotLevel < 1 || slotLevel > 9) {
            throw new ValidationException("A spell slot is of 1st to 9th level, not " + slotLevel);
        }
        String key = slotKey(slots, slotLevel);
        SpellSlot slot = slots.get(key);
        if (slot == null || slot.remaining() < 1) {
            throw new ConflictException(owner + " has no level " + slotLevel + " spell slot left");
        }
        int gained = FontOfMagic.pointsGained(slotLevel, points.currentUses(), points.maxUses());
        if (gained == 0) {
            throw new ConflictException(owner + " already has all " + points.maxUses() + " sorcery points");
        }
        slots.put(key, slot.spend());
        List<ResourcePoolEntry> changed = new ArrayList<>(pools);
        ResourcePoolEntry after = points.withCurrentUses(points.currentUses() + gained);
        changed.replaceAll(pool -> pool == points ? after : pool);
        String capped = gained < slotLevel ? ", the most they can have" : "";
        return new Exchange(slots, changed, "converts a " + (key.startsWith(PACT_SLOT) ? "pact" : "level " + slotLevel)
                + " spell slot into " + gained + " sorcery point" + (gained == 1 ? "" : "s") + capped + " ("
                + after.currentUses() + "/" + after.maxUses() + ")");
    }

    // A level's slots, or a Warlock's pact slots of that level when there are none
    private static String slotKey(Map<String, SpellSlot> slots, int level) {
        String key = String.valueOf(level);
        SpellSlot own = slots.get(key);
        return own != null && own.remaining() > 0 || !slots.containsKey(PACT_SLOT + level) ? key : PACT_SLOT + level;
    }
}
