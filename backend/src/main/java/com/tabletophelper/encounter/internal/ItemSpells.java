package com.tabletophelper.encounter.internal;

import com.tabletophelper.character.CharacterService;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.ClassSpellcasting;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.dto.SpellOption;
import com.tabletophelper.encounter.dto.SpellOption.SourceOption;
import com.tabletophelper.encounter.state.ItemUse;
import com.tabletophelper.encounter.state.SpellSource;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.combat.CastingTime;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The items a player character carries into combat and the spells they cast (D210): a magic item's spells, paid for
 * with its charges (Spell Source ITEM), and spell scrolls, used up as they are read (SCROLL, DMG p.200). What the
 * character carries is its sheet's inventory less the items its participant has used up in the encounter, which
 * Write-back takes off the sheet at its end.
 * <ul>
 *   <li>An item's spells are those its data lists by charge cost. A cast costs that many charges at the spell's level,
 *       or the level the data gives it ("fireball#5"); an item whose text lets more charges raise the level ("for
 *       each additional charge", "1 charge per spell level") casts it one level higher for each, up to the level that
 *       text names. Its save DC and attack bonus are the item's own where its text gives them, as a Wand of Fireballs'
 *       DC 15, and the wielder's otherwise; neither is a Manual Modifier. The charges come from the item's pool, which
 *       the character has only while it carries the linked item, attuned where the item requires it.</li>
 *   <li>A scroll holds the spell its inventory entry names, cast at the scroll's level with the DMG p.200 save DC and
 *       attack bonus for that level, by a character with the spell on one of its classes' spell lists. One of a higher
 *       level than that class can cast takes a DC 10 + level check with the class's spellcasting ability first.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ItemSpells {

    /** The origin of a spell cast with an item's charges, followed by the item's name. */
    static final String ITEM_ORIGIN = "item:";
    /** The origin of a spell cast from a scroll, followed by the scroll's name. */
    static final String SCROLL_ORIGIN = "scroll:";

    // A spell name in the data: "fireball#5" is cast at 5th level, "summon fiend|tce" names its book
    private static final Pattern DATA_SPELL = Pattern.compile("^([^#|]+)(?:\\|[^#]*)?(?:#(\\d))?$");
    private static final Pattern PER_LEVEL = Pattern.compile(
            "charges? per spell level(?:, up to (\\d)(?:st|nd|rd|th))?", Pattern.CASE_INSENSITIVE);
    // An item whose text has chance pick the spell it casts: "cast ... at random", or a d100 table to consult
    private static final Pattern RANDOM_SPELL = Pattern.compile(
            "\\bcast\\b[^.\\n]*\\bat random\\b|\\broll (?:a )?d100 and consult\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EACH_ADDITIONAL = Pattern.compile("for each additional charge", Pattern.CASE_INSENSITIVE);

    // A spell scroll's save DC and attack bonus by spell level, cantrip first (DMG p.200)
    private static final int[] SCROLL_DC = {13, 13, 13, 15, 15, 17, 17, 18, 18, 19};
    private static final int[] SCROLL_ATTACK = {5, 5, 5, 7, 7, 9, 9, 10, 10, 11};

    private final ItemRepository itemRepository;
    private final SpellRepository spellRepository;
    private final CharacterService characterService;
    private final ObjectMapper objectMapper;
    private final CreatureStats stats;
    private final Resolver resolver;

    /**
     * A spell a magic item casts.
     *
     * @param itemName    the inventory entry's name
     * @param item        the item row it is linked to
     * @param spell       the spell, as the spells table has it; null when the table doesn't list it
     * @param dataName    the spell's name as the item's data gives it
     * @param baseLevel   the level the item casts it at for its base charges
     * @param charges     the charges a cast at baseLevel costs
     * @param maxLevel    the highest level more charges can raise it to; baseLevel when they can't
     */
    record ItemSpell(String itemName, Item item, Spell spell, String dataName, int baseLevel, int charges,
                     int maxLevel) {

        String spellName() {
            return spell != null ? spell.getName() : dataName;
        }

        int spellLevel() {
            return spell != null && spell.getLevel() != null ? spell.getLevel() : baseLevel;
        }

        // The charges a cast at that level costs: one more for each level above the base
        int cost(int level) {
            return charges + (level - baseLevel);
        }
    }

    /** The numbers a spell from an item or scroll is cast with, and the ability its MOD adds. */
    record Numbers(Integer attackBonus, Integer saveDc, String ability) {
    }

    /**
     * The check a scroll of a spell too high for its reader takes first (DMG p.200): DC 10 + the spell's level, with
     * the spellcasting ability of the class whose list has the spell.
     */
    record ScrollCheck(int dc, String ability) {
    }

    // ── What is carried ──────────────────────────────────────────

    /** The sheet's inventory of a player character participant; none for a monster. */
    static List<InventoryItem> inventory(EncounterParticipant participant) {
        PlayerCharacter character = participant.getCharacter();
        if (character == null || character.getEquipment() == null) {
            return List.of();
        }
        return character.getEquipment().stream().filter(Objects::nonNull).toList();
    }

    /** How many of the entry with this name, and this spell for a scroll, the participant still carries. */
    static int left(EncounterParticipant participant, String name, String spell) {
        int carried = inventory(participant).stream()
                .filter(entry -> same(entry, name, spell))
                .mapToInt(InventoryItem::carried)
                .sum();
        return Math.max(0, carried - ItemUse.count(participant.getItemsUsed(), name, spell));
    }

    /** Records one of the entry used up, for undo and Write-back (D210). */
    static void useUp(EncounterParticipant participant, String name, String spell) {
        List<ItemUse> used = participant.getItemsUsed() != null
                ? new ArrayList<>(participant.getItemsUsed()) : new ArrayList<>();
        used.add(new ItemUse(name, spell));
        participant.setItemsUsed(used);
    }

    static boolean same(InventoryItem entry, String name, String spell) {
        return entry.name() != null && entry.name().trim().equalsIgnoreCase(name.trim())
                && Objects.equals(lower(entry.spell()), lower(spell));
    }

    /** The item an inventory entry is linked to; empty for one that isn't. */
    Optional<Item> item(InventoryItem entry) {
        return entry.itemId() != null ? itemRepository.findById(entry.itemId()) : Optional.empty();
    }

    /** The first entry the participant carries with this name, a scroll's by its spell too. */
    static Optional<InventoryItem> entry(EncounterParticipant participant, String name, String spell) {
        return inventory(participant).stream().filter(entry -> same(entry, name, spell)).findFirst();
    }

    // ── Magic items' spells ──────────────────────────────────────

    /**
     * The spells each linked item the participant carries casts with its charges, in inventory order; an item carried
     * twice is listed once.
     */
    List<ItemSpell> chargedSpells(EncounterParticipant participant) {
        List<ItemSpell> spells = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (InventoryItem entry : inventory(participant)) {
            if (entry.carried() == 0 || entry.itemId() == null) continue;
            Optional<Item> item = item(entry);
            if (item.isEmpty() || item.get().getAttachedSpells() == null) continue;
            String key = item.get().getName().toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            spells.addAll(spellsOf(entry.name(), item.get()));
        }
        return spells;
    }

    // The spells the item's data lists by charge cost; none for an item whose spell is chosen at random, as a Wand of
    // Wonder's d100 table or a Failed Experiment Wand's "at random": its charges are spent from its pool, and the DM
    // resolves what happens
    List<ItemSpell> spellsOf(String entryName, Item item) {
        if (randomSpell(item)) return List.of();
        JsonNode byCharges = attachedSpells(item).path("charges");
        if (!byCharges.isObject()) return List.of();
        List<ItemSpell> spells = new ArrayList<>();
        int listed = 0;
        for (var group : byCharges.properties()) listed += group.getValue().size();
        for (var group : byCharges.properties()) {
            if (!group.getKey().matches("\\d{1,2}")) continue;
            int charges = Integer.parseInt(group.getKey());
            for (JsonNode name : group.getValue()) {
                Matcher parsed = DATA_SPELL.matcher(name.asString("").trim());
                if (!parsed.matches()) continue;
                String dataName = parsed.group(1).trim();
                Spell spell = spellRepository.findByNameIgnoreCase(dataName).orElse(null);
                int ownLevel = spell != null && spell.getLevel() != null ? spell.getLevel() : 0;
                int baseLevel = parsed.group(2) != null ? Integer.parseInt(parsed.group(2)) : ownLevel;
                spells.add(new ItemSpell(entryName, item, spell, dataName, baseLevel, charges,
                        maxLevel(item, dataName, baseLevel, listed)));
            }
        }
        return spells;
    }

    // The highest level more charges raise the spell to: up to 9th for "each additional charge", or the level a "1
    // charge per spell level, up to 4th" names, for the spell that text is about when the item casts several
    private static int maxLevel(Item item, String dataName, int baseLevel, int listed) {
        String text = item.getDescription() != null ? item.getDescription() : "";
        if (baseLevel == 0) return 0;
        Matcher perLevel = PER_LEVEL.matcher(text);
        if (perLevel.find()) {
            String before = text.substring(Math.max(0, perLevel.start() - dataName.length() - 4), perLevel.start());
            if (listed == 1 || before.toLowerCase(Locale.ROOT).contains(dataName.toLowerCase(Locale.ROOT))) {
                return perLevel.group(1) != null ? Math.max(baseLevel, Integer.parseInt(perLevel.group(1))) : 9;
            }
        }
        return listed == 1 && EACH_ADDITIONAL.matcher(text).find() ? 9 : baseLevel;
    }

    /**
     * Whether chance picks the spell the item casts, as a Wand of Wonder's d100 table (DMG p.212) or a Failed Experiment
     * Wand's "at random": the player then chooses none of its spells (D210).
     */
    static boolean randomSpell(Item item) {
        return item.getDescription() != null && RANDOM_SPELL.matcher(item.getDescription()).find();
    }

    private JsonNode attachedSpells(Item item) {
        try {
            return objectMapper.readTree(item.getAttachedSpells());
        } catch (JacksonException e) {
            throw new ConflictException("The spells of " + item.getName() + " can't be read");
        }
    }

    /**
     * Why the participant can't cast the item's spell now, or null: the item needs attuning, or its charges weren't
     * brought into the encounter, or the spells table doesn't list the spell.
     */
    String unavailable(EncounterParticipant participant, ItemSpell spell) {
        String itemReason = unavailable(participant, spell.itemName(), spell.item());
        if (itemReason != null) {
            return itemReason;
        }
        if (spell.spell() == null) {
            return spell.dataName() + " isn't in the spells table, so the DM resolves " + spell.item().getName()
                    + "'s spell";
        }
        return null;
    }

    /**
     * Why the participant can't use the item's charges now, or null: it must be attuned to an item that requires it
     * (DMG p.136), and the charges must have come into the encounter with its sheet.
     */
    String unavailable(EncounterParticipant participant, String itemName, Item item) {
        if (Boolean.TRUE.equals(item.getRequiresAttunement()) && inventory(participant).stream()
                .noneMatch(entry -> same(entry, itemName, null) && Boolean.TRUE.equals(entry.attuned()))) {
            return participant.getDisplayName() + " must be attuned to " + item.getName() + " to use its charges";
        }
        if (pool(participant, item) == null) {
            return item.getName() + "'s charges weren't on " + participant.getDisplayName()
                    + "'s sheet when the encounter started";
        }
        return null;
    }

    /** The item's charges on the participant; null when it hasn't the pool. */
    static ResourcePoolEntry pool(EncounterParticipant participant, Item item) {
        String poolId = ResourcePoolEntry.itemPoolId(item.getName());
        return participant.getResourcePoolsCurrent() == null ? null : participant.getResourcePoolsCurrent().stream()
                .filter(pool -> pool != null && poolId.equals(pool.poolId())).findFirst().orElse(null);
    }

    /** The spell as the cast flow lists it, with the item's charges as its one source. */
    SpellOption option(EncounterParticipant participant, ItemSpell spell) {
        ResourcePoolEntry pool = pool(participant, spell.item());
        int left = pool != null ? pool.currentUses() : 0;
        int max = pool != null ? pool.maxUses() : spell.item().getCharges() != null ? spell.item().getCharges() : 0;
        String reason = unavailable(participant, spell);
        List<Integer> levels = new ArrayList<>();
        Map<String, Integer> costs = new LinkedHashMap<>();
        for (int level = spell.baseLevel(); level <= spell.maxLevel(); level++) {
            costs.put(String.valueOf(level), spell.cost(level));
            if (reason == null && spell.cost(level) <= left) levels.add(level);
        }
        return new SpellOption(spell.spellName(), spell.spellLevel(), castingTime(spell.spell()), null,
                List.of(new SourceOption(SpellSource.ITEM, levels, left, max, spell.itemName(), costs)), reason);
    }

    // ── Scrolls ──────────────────────────────────────────────────

    /** A spell scroll the participant carries, with the spell its entry records. */
    record Scroll(InventoryItem entry, Item item, Spell spell, int left) {
    }

    /** The spell scrolls the participant carries with a spell recorded, one for each entry with any left. */
    List<Scroll> scrolls(EncounterParticipant participant) {
        List<Scroll> scrolls = new ArrayList<>();
        for (InventoryItem entry : inventory(participant)) {
            if (entry.spell() == null || entry.itemId() == null) continue;
            int left = left(participant, entry.name(), entry.spell());
            if (left == 0 || scrolls.stream().anyMatch(s -> same(s.entry(), entry.name(), entry.spell()))) continue;
            Optional<Item> item = item(entry).filter(found -> found.getSpellScrollLevel() != null);
            Optional<Spell> spell = spellRepository.findByNameIgnoreCase(entry.spell());
            if (item.isPresent() && spell.isPresent()) {
                scrolls.add(new Scroll(entry, item.get(), spell.get(), left));
            }
        }
        return scrolls;
    }

    /** Why the participant can't read the scroll, or null: its spell is on none of its classes' spell lists. */
    String unavailable(EncounterParticipant participant, Scroll scroll) {
        return readingClass(participant, scroll.spell()).isPresent() ? null
                : scroll.spell().getName() + " isn't on any of " + participant.getDisplayName()
                + "'s classes' spell lists, so they can't read the scroll (DMG p.200)";
    }

    /** The scroll as the cast flow lists it, its copies left as its one source. */
    SpellOption option(EncounterParticipant participant, Scroll scroll) {
        String reason = unavailable(participant, scroll);
        int level = scroll.item().getSpellScrollLevel();
        return new SpellOption(scroll.spell().getName(), scroll.spell().getLevel() != null ? scroll.spell().getLevel() : 0,
                castingTime(scroll.spell()), null, List.of(new SourceOption(SpellSource.SCROLL,
                reason == null ? List.of(level) : List.of(), scroll.left(), null, scroll.entry().name(), null)), reason);
    }

    /** The save DC a scroll of that level casts with (DMG p.200). */
    static int scrollSaveDc(int level) {
        return SCROLL_DC[Math.max(0, Math.min(9, level))];
    }

    /** The attack bonus a scroll of that level casts with (DMG p.200). */
    static int scrollAttackBonus(int level) {
        return SCROLL_ATTACK[Math.max(0, Math.min(9, level))];
    }

    /**
     * The class a character reads the scroll's spell with: one whose spell list has it, the one able to cast the
     * highest level first (DMG p.200); empty when no class of the character's has it.
     */
    Optional<ClassSpellcasting> readingClass(EncounterParticipant participant, Spell spell) {
        PlayerCharacter character = participant.getCharacter();
        if (character == null) return Optional.empty();
        List<String> lists = classLists(spell);
        return characterService.spellcasting(character).stream()
                .filter(casting -> lists.stream().anyMatch(list -> list.equalsIgnoreCase(casting.spellList())))
                .max((a, b) -> Integer.compare(a.highestSpellLevel(), b.highestSpellLevel()));
    }

    /** The check a scroll's spell takes first, when it is of a higher level than its reader can cast; empty otherwise. */
    Optional<ScrollCheck> scrollCheck(EncounterParticipant participant, Spell spell) {
        int level = spell.getLevel() != null ? spell.getLevel() : 0;
        return readingClass(participant, spell)
                .filter(casting -> level > casting.highestSpellLevel())
                .map(casting -> new ScrollCheck(10 + level, casting.ability()));
    }

    /** The check a scroll of the named spell takes first; empty when it takes none, or the table doesn't list it. */
    Optional<ScrollCheck> scrollCheck(EncounterParticipant participant, String spellName) {
        return spellRepository.findByNameIgnoreCase(spellName).flatMap(spell -> scrollCheck(participant, spell));
    }

    /**
     * A check rolled.
     *
     * @param description how the log shows it, such as "a DC 13 Intelligence check: d20(12) + 3 = 15, a success"
     */
    record CheckRoll(boolean success, String description) {
    }

    /**
     * Rolls the scroll's check: d20 plus the ability's modifier, with the advantage and disadvantage the reader has on
     * ability checks, such as exhaustion's (D199).
     *
     * @param attackBonus the reader's spell attack bonus, which gives the modifier only when the ability isn't known
     */
    CheckRoll rollScrollCheck(EncounterParticipant reader, ScrollCheck check, int attackBonus) {
        Optional<Ability> ability = Ability.parse(check.ability());
        int modifier = stats.spellcastingModifier(reader, check.ability(), attackBonus);
        AbilityChecks.Roll rolled = AbilityChecks.roll(resolver::d20,
                AbilityChecks.sources(reader, ability.orElse(null), null));
        int total = rolled.d20() + modifier;
        boolean success = total >= check.dc();
        return new CheckRoll(success, "a DC " + check.dc() + " " + ability.map(Ability::fullName)
                .map(name -> Character.toUpperCase(name.charAt(0)) + name.substring(1)).orElse("spellcasting")
                + " check: " + rolled.dice() + (modifier >= 0 ? " + " + modifier : " - " + (-modifier)) + " = " + total
                + (success ? ", a success" : ", a failure"));
    }

    private List<String> classLists(Spell spell) {
        if (spell.getClasses() == null || spell.getClasses().isBlank()) return List.of();
        try {
            return objectMapper.readValue(spell.getClasses(), new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            throw new ConflictException("The class lists of " + spell.getName() + " can't be read");
        }
    }

    // ── Paying ───────────────────────────────────────────────────

    /**
     * How the caster pays for a spell from an item (ITEM) or a scroll (SCROLL) named by its inventory entry, which
     * nothing has spent yet. slotLevel is the level to cast at, 0 for the item's base level or the scroll's own.
     *
     * @throws ValidationException for a monster, an item or scroll the character doesn't carry, a spell it doesn't
     *                             cast, a level it can't reach, or a spell on none of the reader's spell lists
     * @throws ConflictException   for an item without the charges left
     */
    SpellSources.Payment payment(EncounterParticipant caster, String spellName, SpellSource source, String itemName,
                                 int slotLevel) {
        if (caster.getCharacter() == null) {
            throw new ValidationException("Only a player character's items can be used: the DM resolves a monster's");
        }
        if (itemName == null || itemName.isBlank()) {
            throw new ValidationException("Name the item " + spellName + " is cast " + (source == SpellSource.SCROLL
                    ? "from" : "with"));
        }
        String name = itemName.trim();
        return source == SpellSource.SCROLL ? scrollPayment(caster, spellName.trim(), name, slotLevel)
                : itemPayment(caster, spellName.trim(), name, slotLevel);
    }

    private SpellSources.Payment itemPayment(EncounterParticipant caster, String spellName, String itemName,
                                             int slotLevel) {
        InventoryItem entry = entry(caster, itemName, null).filter(found -> found.carried() > 0)
                .orElseThrow(() -> new ValidationException(caster.getDisplayName() + " doesn't carry " + itemName));
        Item item = item(entry).orElseThrow(() -> new ValidationException(itemName + " isn't linked to its item"));
        ItemSpell spell = spellsOf(entry.name(), item).stream()
                .filter(candidate -> candidate.spellName().equalsIgnoreCase(spellName)
                        || candidate.dataName().equalsIgnoreCase(spellName))
                .findFirst()
                .orElseThrow(() -> new ValidationException(item.getName() + " doesn't cast " + spellName));
        String reason = unavailable(caster, spell);
        if (reason != null) {
            throw new ValidationException(reason);
        }
        int level = slotLevel == 0 ? spell.baseLevel() : slotLevel;
        if (level < spell.baseLevel() || level > spell.maxLevel()) {
            throw new ValidationException(item.getName() + " casts " + spell.spellName() + " at level "
                    + (spell.maxLevel() == spell.baseLevel() ? String.valueOf(spell.baseLevel())
                    : spell.baseLevel() + " to " + spell.maxLevel()));
        }
        int cost = spell.cost(level);
        ResourcePoolEntry pool = pool(caster, item);
        if (pool.currentUses() < cost) {
            throw new ConflictException(item.getName() + " has " + pool.currentUses() + " of " + pool.maxUses()
                    + " charges left, and " + spell.spellName() + " at level " + level + " needs " + cost);
        }
        return new SpellSources.Payment(spell.spellName(), spell.spellLevel(), SpellSource.ITEM, level, null, pool,
                ITEM_ORIGIN + item.getName(), castingTime(spell.spell()), cost, entry.name());
    }

    private SpellSources.Payment scrollPayment(EncounterParticipant caster, String spellName, String scrollName,
                                               int slotLevel) {
        Scroll scroll = scrolls(caster).stream()
                .filter(candidate -> same(candidate.entry(), scrollName, candidate.entry().spell())
                        && candidate.spell().getName().equalsIgnoreCase(spellName))
                .findFirst()
                .orElseThrow(() -> new ValidationException(caster.getDisplayName() + " carries no " + scrollName
                        + " of " + spellName));
        String reason = unavailable(caster, scroll);
        if (reason != null) {
            throw new ValidationException(reason);
        }
        int level = scroll.item().getSpellScrollLevel();
        if (slotLevel != 0 && slotLevel != level) {
            throw new ValidationException("A spell scroll casts its spell at the scroll's level, " + level);
        }
        return new SpellSources.Payment(scroll.spell().getName(), scroll.spell().getLevel() != null
                ? scroll.spell().getLevel() : 0, SpellSource.SCROLL, level, null, null,
                SCROLL_ORIGIN + scroll.entry().name(), castingTime(scroll.spell()), 1, scroll.entry().name());
    }

    /**
     * The numbers the spell is cast with: a scroll's from DMG p.200 by its level, with the reading class's ability; an
     * item's own where its text gives them, else the wielder's main ones, with its main spellcasting ability.
     */
    Numbers numbers(EncounterParticipant caster, SpellSources.Payment payment) {
        PlayerCharacter character = caster.getCharacter();
        if (payment.source() == SpellSource.SCROLL) {
            String ability = spellRepository.findByNameIgnoreCase(payment.spellName())
                    .flatMap(spell -> readingClass(caster, spell)).map(ClassSpellcasting::ability).orElse(null);
            return new Numbers(scrollAttackBonus(payment.castLevel()), scrollSaveDc(payment.castLevel()), ability);
        }
        Item item = entry(caster, payment.itemName(), null).flatMap(this::item).orElse(null);
        Integer saveDc = item != null && item.getSpellSaveDc() != null ? item.getSpellSaveDc() : caster.getSpellSaveDc();
        Integer attack = item != null && item.getSpellAttackBonus() != null ? item.getSpellAttackBonus()
                : caster.getSpellAttackBonus();
        return new Numbers(attack, saveDc, character != null ? characterService.mainSpellcastingAbility(character)
                : caster.getSpellcastingAbility());
    }

    // What casting the spell costs from a turn (D174); one the table doesn't list takes an action
    private static CastingTime castingTime(Spell spell) {
        if (spell == null || spell.getCastingTime() == null) return CastingTime.ACTION;
        try {
            return CastingTime.of(spell.getCastingTime());
        } catch (IllegalArgumentException e) {
            return CastingTime.ACTION;
        }
    }

    private static String lower(String text) {
        return text == null || text.isBlank() ? null : text.trim().toLowerCase(Locale.ROOT);
    }
}
