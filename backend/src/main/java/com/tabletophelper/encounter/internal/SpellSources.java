package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.dto.SpellOption.SourceOption;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.rules.combat.CastingTime;
import com.tabletophelper.shared.ValidationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which Spell Sources pay for a caster's spells (D164, D165), as pure functions of the caster's spells and what
 * it has left to spend.
 * <ul>
 *   <li>A cantrip, and a spell its caster has at will, costs nothing (NONE).</li>
 *   <li>A spell with uses of its own, such as a monster's 1/day or a race's once per long rest, spends one
 *       (INNATE). It is cast at its own level, with no upcasting.</li>
 *   <li>Any other levelled spell needs a slot of at least its level (SLOT), or a pact slot, which casts it at the
 *       pact slots' level (PACT_SLOT). A player character's race and feat spells can be cast with a slot too.</li>
 *   <li>A player character casts only what they can cast now: a levelled spell from a class that prepares its
 *       spells must be prepared or always prepared, and a race spell must be unlocked at the character's level.</li>
 * </ul>
 */
final class SpellSources {

    /** The origin of a spell from a monster's stat block. */
    static final String MONSTER = "monster";

    // The classes that prepare their spells each day rather than knowing them (PHB 2014, and the Artificer)
    private static final Set<String> PREPARED_CASTERS = Set.of("Artificer", "Cleric", "Druid", "Paladin", "Wizard");

    private SpellSources() {
    }

    /**
     * A spell as its caster has it.
     *
     * @param name           its name, as the spells table has it when the table lists it
     * @param level          its level in the spells table, or as recorded when the table doesn't list it
     * @param origin         where it comes from: "class:Wizard", "race:Tiefling", "feat:Fey Touched" or "monster"
     * @param prepared       a prepared caster has it prepared
     * @param alwaysPrepared it is always prepared, as a subclass's spells are
     * @param atWill         it is cast without a slot or a use
     * @param uses           its own uses between long rests, such as a monster's daily uses; null for none
     * @param unlocksAtLevel the character level it becomes available at; null for none
     * @param castingTime    its casting time as the spells table has it, such as "1 bonus action"; null when the
     *                       table doesn't list the spell
     */
    record Known(String name, int level, String origin, boolean prepared, boolean alwaysPrepared, boolean atWill,
                 Integer uses, Integer unlocksAtLevel, String castingTime) {

        Known(String name, int level, String origin, boolean prepared, boolean alwaysPrepared, boolean atWill,
              Integer uses, Integer unlocksAtLevel) {
            this(name, level, origin, prepared, alwaysPrepared, atWill, uses, unlocksAtLevel, null);
        }

        boolean fromMonster() {
            return MONSTER.equals(origin);
        }

        // The class it comes from, when that is recorded
        String className() {
            return origin != null && origin.startsWith("class:") ? origin.substring("class:".length()) : null;
        }

        boolean hasOwnUses() {
            return uses != null && uses > 0 && !atWill;
        }
    }

    /**
     * What a caster has left to spend.
     *
     * @param slots          its spell slots, keyed "1" to "9" and "pact_N"
     * @param pools          its resource pools, which hold its innate uses
     * @param characterLevel a player character's level, which unlocks race spells; null for a monster
     */
    record Purse(Map<String, SpellSlot> slots, List<ResourcePoolEntry> pools, Integer characterLevel) {

        Purse {
            slots = slots != null ? slots : Map.of();
            pools = pools != null ? pools : List.of();
        }
    }

    /**
     * How a cast is paid for.
     *
     * @param spellName  the spell's name, as the spells table has it
     * @param castLevel  the level it is cast at: the slot's, the pact slots', or the spell's own
     * @param slotKey    the slot spent, such as "3" or "pact_3", for SLOT and PACT_SLOT
     * @param innatePool the pool that holds the spell's innate uses, full, as a caster without it starts it
     * @param origin     where the spell cast comes from, such as "class:Wizard" or "race:Tiefling": the entry with the
     *                   innate use for INNATE, and otherwise a class's entry when the caster has the spell from one
     * @param castingTime what casting it costs from a turn (D174)
     */
    record Payment(String spellName, int spellLevel, SpellSource source, int castLevel, String slotKey,
                   ResourcePoolEntry innatePool, String origin, CastingTime castingTime) {
    }

    // ── Options ──────────────────────────────────────────────────

    /**
     * Each spell the caster can cast now, with what can pay for it, by level and then name. A spell its caster has
     * from two places, such as a class and a race, is listed once with the sources of both. A spell that no source
     * could ever pay for, such as a level 3 spell for a caster with no slot above level 2, is left out.
     */
    static List<SpellOption> options(List<Known> spells, Purse purse) {
        Map<String, List<Known>> byName = new LinkedHashMap<>();
        for (Known known : spells) {
            if (known.name() != null && !known.name().isBlank() && castableNow(known, purse)) {
                byName.computeIfAbsent(key(known.name()), name -> new ArrayList<>()).add(known);
            }
        }
        List<SpellOption> options = new ArrayList<>();
        for (List<Known> entries : byName.values()) {
            SpellOption option = option(entries, purse);
            if (!option.sources().isEmpty()) {
                options.add(option);
            }
        }
        options.sort(Comparator.comparingInt(SpellOption::level)
                .thenComparing(SpellOption::name, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    // The spell's sources, from entries of one spell its caster can cast now
    private static SpellOption option(List<Known> entries, Purse purse) {
        Known first = entries.getFirst();
        int level = first.level();
        List<SourceOption> sources = new ArrayList<>();
        if (level > 0 && entries.stream().anyMatch(SpellSources::paidWithSlots)) {
            slotSource(level, purse).ifPresent(sources::add);
            pactSource(level, purse).ifPresent(sources::add);
        }
        if (level > 0) {
            entries.stream()
                    .filter(Known::hasOwnUses)
                    .findFirst()
                    .ifPresent(innate -> sources.add(innateSource(first.name(), level, innate.uses(), purse)));
        }
        if (level == 0 || entries.stream().anyMatch(Known::atWill)) {
            sources.add(new SourceOption(SpellSource.NONE, List.of(level), null, null));
        }
        return new SpellOption(first.name(), level, castingTime(first), reactionTrigger(first), sources, null);
    }

    /**
     * What casting the spell costs from a turn (D174), from its casting time in the spells table; a spell the table
     * doesn't list, or whose casting time can't be read, takes an action.
     */
    static CastingTime castingTime(Known known) {
        if (known.castingTime() == null || known.castingTime().isBlank()) {
            return CastingTime.ACTION;
        }
        try {
            return CastingTime.of(known.castingTime());
        } catch (IllegalArgumentException e) {
            return CastingTime.ACTION;
        }
    }

    // What a reaction spell is cast in response to, from its casting time: "which you take when ..."
    private static String reactionTrigger(Known known) {
        if (castingTime(known) != CastingTime.REACTION) {
            return null;
        }
        int comma = known.castingTime().indexOf(',');
        String trigger = comma >= 0 ? known.castingTime().substring(comma + 1).trim() : "";
        return trigger.isEmpty() ? null : trigger;
    }

    private static boolean castableNow(Known known, Purse purse) {
        if (known.fromMonster()) {
            return true;
        }
        if (known.unlocksAtLevel() != null && purse.characterLevel() != null
                && known.unlocksAtLevel() > purse.characterLevel()) {
            return false;
        }
        boolean needsPreparing = known.level() > 0 && known.className() != null
                && PREPARED_CASTERS.contains(known.className()) && !known.atWill() && !known.hasOwnUses();
        return !needsPreparing || known.prepared() || known.alwaysPrepared();
    }

    // A monster casts with a slot only what it has neither at will nor by the day; a player character casts any
    // spell they have that isn't at will with a slot, race and feat spells included
    private static boolean paidWithSlots(Known known) {
        return !known.atWill() && (!known.fromMonster() || !known.hasOwnUses());
    }

    // The slot levels from the spell's own up: none when the caster has no slot that high
    private static Optional<SourceOption> slotSource(int level, Purse purse) {
        List<Integer> levels = new ArrayList<>();
        boolean any = false;
        for (int slotLevel = level; slotLevel <= 9; slotLevel++) {
            SpellSlot slot = purse.slots().get(String.valueOf(slotLevel));
            if (slot != null && slot.max() > 0) {
                any = true;
                if (slot.remaining() > 0) {
                    levels.add(slotLevel);
                }
            }
        }
        return any ? Optional.of(new SourceOption(SpellSource.SLOT, levels, null, null)) : Optional.empty();
    }

    // The pact slots, all of the Warlock's slot level, when that is at least the spell's
    private static Optional<SourceOption> pactSource(int level, Purse purse) {
        return purse.slots().entrySet().stream()
                .filter(entry -> pactLevel(entry.getKey()) != null && entry.getValue().max() > 0)
                .filter(entry -> pactLevel(entry.getKey()) >= level)
                .max(Comparator.comparingInt(entry -> pactLevel(entry.getKey())))
                .map(entry -> {
                    SpellSlot slot = entry.getValue();
                    int pactLevel = pactLevel(entry.getKey());
                    return new SourceOption(SpellSource.PACT_SLOT,
                            slot.remaining() > 0 ? List.of(pactLevel) : List.of(), slot.remaining(), slot.max());
                });
    }

    // A caster without the spell's pool yet has all of its uses
    private static SourceOption innateSource(String spellName, int level, int uses, Purse purse) {
        ResourcePoolEntry pool = pool(purse, innatePoolId(spellName));
        int max = pool != null ? pool.maxUses() : uses;
        int left = pool != null ? pool.currentUses() : uses;
        return new SourceOption(SpellSource.INNATE, left > 0 ? List.of(level) : List.of(), left, max);
    }

    // ── Payment ──────────────────────────────────────────────────

    /**
     * How the caster pays for casting the spell with the source asked for, at slotLevel: a slot's level, the pact
     * slots' level, or for INNATE and NONE the spell's own level or 0. Without a source, a slot level above 0 means
     * a slot (or a pact slot of that level when the caster has no slot of it left), and 0 means nothing for a
     * cantrip or at-will spell, and otherwise an innate use. Anything the caster can't pay for is a 400.
     */
    static Payment pay(String casterName, List<Known> spells, Purse purse, String spellName, SpellSource requested,
                       int slotLevel) {
        List<Known> entries = spells.stream()
                .filter(known -> known.name() != null && key(known.name()).equals(key(spellName)))
                .toList();
        if (entries.isEmpty()) {
            throw new ValidationException(casterName + " can't cast " + spellName + ": it isn't one of their spells");
        }
        List<Known> castable = entries.stream().filter(known -> castableNow(known, purse)).toList();
        if (castable.isEmpty()) {
            throw new ValidationException(notCastableNow(casterName, entries.getFirst(), purse));
        }
        SpellOption option = option(castable, purse);
        String name = option.name();
        int level = option.level();
        if (option.sources().isEmpty()) {
            throw new ValidationException(casterName + " has no spell slot of level " + level
                    + " or higher, which " + name + " needs");
        }

        SpellSource source = requested != null ? requested : inferred(option, slotLevel);
        SourceOption chosen = source(option, source).orElseThrow(() -> new ValidationException(source == SpellSource.NONE
                ? name + " is a level " + level + " spell, so it needs a spell slot, a pact slot or an innate use"
                : name + " can't be cast " + with(source)));
        String origin = origin(castable);
        CastingTime castingTime = option.castingTime();
        return switch (source) {
            case SLOT -> {
                if (slotLevel < level) {
                    throw new ValidationException(name + " is a level " + level
                            + " spell, so it needs a slot of level " + level + " or higher");
                }
                if (!chosen.levels().contains(slotLevel)) {
                    throw new ValidationException(casterName + " has no level " + slotLevel + " spell slots left");
                }
                yield new Payment(name, level, SpellSource.SLOT, slotLevel, String.valueOf(slotLevel), null, origin,
                        castingTime);
            }
            case PACT_SLOT -> {
                if (chosen.levels().isEmpty()) {
                    throw new ValidationException(casterName + " has no pact slots left");
                }
                int pactLevel = chosen.levels().getFirst();
                if (slotLevel != 0 && slotLevel != pactLevel) {
                    throw new ValidationException("Pact slots are level " + pactLevel + ", so a pact slot casts "
                            + name + " at level " + pactLevel);
                }
                yield new Payment(name, level, SpellSource.PACT_SLOT, pactLevel, "pact_" + pactLevel, null, origin,
                        castingTime);
            }
            case INNATE -> {
                if (slotLevel != 0 && slotLevel != level) {
                    throw new ValidationException(name + " is cast at its own level, " + level
                            + ", with an innate use: innate spells aren't upcast");
                }
                if (chosen.levels().isEmpty()) {
                    throw new ValidationException(casterName + " has no uses of " + name + " left");
                }
                Known innate = castable.stream().filter(Known::hasOwnUses).findFirst().orElseThrow();
                yield new Payment(name, level, SpellSource.INNATE, level, null,
                        innatePool(name, innate.uses(), sourceType(innate.origin()), sourceName(innate.origin())),
                        innate.origin(), castingTime);
            }
            case NONE -> {
                if (slotLevel != 0 && slotLevel != level) {
                    throw new ValidationException(level == 0
                            ? name + " is a cantrip, so it is cast without a slot"
                            : name + " is cast at will, at its own level, " + level);
                }
                yield new Payment(name, level, SpellSource.NONE, level, null, null, origin, castingTime);
            }
        };
    }

    /**
     * Where a spell its caster has from several places is cast from, unless an innate use pays: a class it has the
     * spell from, whose save DC and attack bonus it then uses (D164), or else the first place.
     */
    static String origin(List<Known> entries) {
        return entries.stream()
                .map(Known::origin)
                .filter(origin -> origin != null && origin.startsWith("class:"))
                .findFirst()
                .orElse(entries.isEmpty() ? null : entries.getFirst().origin());
    }

    private static SpellSource inferred(SpellOption option, int slotLevel) {
        Optional<SourceOption> slot = source(option, SpellSource.SLOT);
        Optional<SourceOption> pact = source(option, SpellSource.PACT_SLOT);
        // A slot level above 0 asks for a slot, unless the spell takes none and that is its own level
        if (slotLevel > 0 && (slot.isPresent() || pact.isPresent() || slotLevel != option.level())) {
            if (slot.isPresent() && slot.get().levels().contains(slotLevel)) return SpellSource.SLOT;
            if (pact.isPresent() && pact.get().levels().contains(slotLevel)) return SpellSource.PACT_SLOT;
            // Nothing of that level is left: let the slot say why
            return slot.isPresent() || pact.isEmpty() ? SpellSource.SLOT : SpellSource.PACT_SLOT;
        }
        if (source(option, SpellSource.NONE).isPresent()) return SpellSource.NONE;
        if (source(option, SpellSource.INNATE).isPresent()) return SpellSource.INNATE;
        return SpellSource.NONE;
    }

    private static String notCastableNow(String casterName, Known known, Purse purse) {
        if (known.unlocksAtLevel() != null && purse.characterLevel() != null
                && known.unlocksAtLevel() > purse.characterLevel()) {
            return casterName + " can cast " + known.name() + " from level " + known.unlocksAtLevel();
        }
        return casterName + " hasn't prepared " + known.name();
    }

    private static Optional<SourceOption> source(SpellOption option, SpellSource source) {
        return option.sources().stream().filter(candidate -> candidate.source() == source).findFirst();
    }

    private static String with(SpellSource source) {
        return switch (source) {
            case SLOT -> "with a spell slot";
            case PACT_SLOT -> "with a pact slot";
            case INNATE -> "with an innate use";
            case NONE -> "without a slot or a use";
        };
    }

    // ── Innate pools ─────────────────────────────────────────────

    /**
     * The resource pool that holds a spell's innate uses: "innate:" and its name in kebab case, the pool a character
     * sheet keeps them in too (D165).
     */
    static String innatePoolId(String spellName) {
        return ResourcePoolEntry.innatePoolId(spellName);
    }

    /** A full pool of a spell's innate uses, which come back on a long rest. */
    static ResourcePoolEntry innatePool(String spellName, int uses, String sourceType, String sourceName) {
        return ResourcePoolEntry.innate(spellName, uses, sourceType, sourceName);
    }

    // RACE or FEAT for a player character's spell, MONSTER for a monster's
    private static String sourceType(String origin) {
        if (origin == null || MONSTER.equals(origin)) return "MONSTER";
        if (origin.startsWith("race:")) return "RACE";
        if (origin.startsWith("feat:")) return "FEAT";
        return "CLASS";
    }

    private static String sourceName(String origin) {
        return origin != null && origin.contains(":") ? origin.substring(origin.indexOf(':') + 1) : null;
    }

    private static ResourcePoolEntry pool(Purse purse, String poolId) {
        return purse.pools().stream().filter(pool -> poolId.equals(pool.poolId())).findFirst().orElse(null);
    }

    private static Integer pactLevel(String slotKey) {
        if (slotKey == null || !slotKey.startsWith("pact_")) {
            return null;
        }
        try {
            return Integer.valueOf(slotKey.substring("pact_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(String spellName) {
        return spellName.trim().toLowerCase(Locale.ROOT);
    }
}
