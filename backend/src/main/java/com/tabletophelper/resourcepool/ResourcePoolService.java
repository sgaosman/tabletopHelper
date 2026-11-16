package com.tabletophelper.resourcepool;

import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.character.state.InventoryItem;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.Item;
import com.tabletophelper.reference.ItemRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.PoolLevelTable;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.RaceFeaturePool;
import com.tabletophelper.reference.RaceFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.Expression;
import com.tabletophelper.rules.ExpressionException;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of resource pools across character sheets,
 * encounter participants, and rest recovery.
 *
 * <h3>Pool lifecycle</h3>
 * <ol>
 *   <li>reconciledPools — the pools a character sheet should have, which CharacterStats writes after every change</li>
 *   <li>A player character's participant copies the sheet's pools when it joins, through character's
 *       CombatSnapshot (D166)</li>
 *   <li>Spend/recover during combat — tracked independently on participant</li>
 *   <li>Write-back applies the change in each pool's uses to the sheet when the DM completes the encounter with its
 *       results applied, through character's WriteBackService (D166)</li>
 *   <li>resetPools — applies rest recovery (short/long) or per-turn resets</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    /**
     * Result of resetting pools, containing both the updated pool list
     * and details of any recharge rolls that were evaluated.
     */
    public record ResetPoolsResult(
            List<ResourcePoolEntry> pools,
            List<RechargeLogEntry> rechargeResults
    ) {}

    /**
     * Details of a single recharge roll, used for combat log entries.
     */
    public record RechargeLogEntry(
            String poolId,
            String displayName,
            boolean success,
            int rollValue,
            String checkExpression
    ) {}

    private final ClassFeaturePoolRepository classFeaturePoolRepository;
    private final RaceFeaturePoolRepository raceFeaturePoolRepository;
    private final ObjectMapper objectMapper;
    private final ItemRepository itemRepository;

    // ── Character sheet ──────────────────────────────────────────

    /**
     * The pools a character should have, from the class feature definitions for its classes and subclasses, the race
     * feature definitions its race row grants at its total level (M24.5b, D208), such as a Dragonborn's Breath Weapon, one
     * {@code innate:<spell>} pool for each spell it can cast now with uses of its own (D165), a
     * {@code mystic-arcanum:<level>} pool for each Mystic Arcanum spell it knows (PHB p.108), and one
     * {@code feat:<feat>} pool for each feat with limited uses that its level history records or the DM granted, such
     * as Lucky's (D158). A pool it already has keeps its place and the uses it has spent; one whose source is gone is
     * dropped. A definition's level table, read at the level of the class it names, gives the pool's maximum, its
     * rest, its die ({@code metadata.die}) and whether it is unlimited ({@code metadata.unlimited}) (D169).
     * Changes nothing, and a failure stops the command that asked rather than leaving the pools half worked out
     * (D155).
     *
     * @param context the variables the maximums' formulas read, as {@link #buildCharacterContext} gives them
     */
    public List<ResourcePoolEntry> reconciledPools(PlayerCharacter character, Map<String, Integer> context) {
        Map<String, ResourcePoolEntry> byPoolId = orEmpty(character.getResourcePools()).stream()
                .collect(Collectors.toMap(ResourcePoolEntry::poolId, e -> e, (a, b) -> a, LinkedHashMap::new));
        Set<String> desiredPoolIds = new LinkedHashSet<>();

        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            UUID classId = entry.classId();
            List<ClassFeaturePool> featurePools = entry.subclassId() != null
                    ? classFeaturePoolRepository.findByClassIdAndLevelAndSubclass(classId, entry.level(),
                    entry.subclassId())
                    : classFeaturePoolRepository.findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(
                    classId, entry.level());

            for (var fp : featurePools) {
                ResourcePoolDefinition def = fp.getPoolDefinition();
                if (def == null) continue;
                desiredPoolIds.add(def.getPoolId());

                PoolLevelTable.Row row = levelRow(def, character);
                String formula = row != null && row.maxUses() != null ? row.maxUses() : def.getMaxUsesFormula();
                int maxUses = evaluateMaxUses(formula, context, def.getPoolId());
                byPoolId.put(def.getPoolId(), buildEntry(def, row, formula, maxUses,
                        keptUses(byPoolId.get(def.getPoolId()), maxUses)));
            }
        }
        shareChannelDivinity(character, byPoolId, desiredPoolIds);

        int characterLevel = character.getLevel() != null ? character.getLevel() : 1;
        // A race's pools, by its row, so a subrace has its own (D208); a new race's pools come in and the old one's go
        // below with the other orphans
        Race race = character.getRaceRef();
        if (race != null && race.getId() != null) {
            for (RaceFeaturePool rp : raceFeaturePoolRepository.findByRaceIdAndMinLevelLessThanEqual(race.getId(),
                    characterLevel)) {
                ResourcePoolDefinition def = rp.getPoolDefinition();
                if (def == null || !desiredPoolIds.add(def.getPoolId())) continue;
                int maxUses = evaluateMaxUses(def.getMaxUsesFormula(), context, def.getPoolId());
                byPoolId.put(def.getPoolId(), buildEntry(def, null, def.getMaxUsesFormula(), maxUses,
                        keptUses(byPoolId.get(def.getPoolId()), maxUses)).withSourceName(race.getName()));
            }
        }

        for (KnownSpell spell : orEmpty(character.getSpellsKnown())) {
            if (!hasInnateUses(spell, characterLevel)) continue;
            String poolId = ResourcePoolEntry.innatePoolId(spell.name());
            // A spell known from two places has one pool, with the first's uses
            if (!desiredPoolIds.add(poolId)) continue;

            int uses = spell.usesPerLongRest();
            byPoolId.put(poolId, ResourcePoolEntry.innate(spell.name().trim(), uses, sourceType(spell.source()),
                    sourceName(spell.source())).withCurrentUses(keptUses(byPoolId.get(poolId), uses)));
        }

        // A Mystic Arcanum is cast once without a slot between long rests (PHB p.108): one pool for each arcanum spell
        // the warlock knows, by its level, so the use spent stays spent if the level's spell changes
        for (KnownSpell spell : orEmpty(character.getSpellsKnown())) {
            if (!KnownSpell.isMysticArcanum(spell) || spell.name() == null || spell.name().isBlank()
                    || spell.level() < 1) {
                continue;
            }
            String poolId = ResourcePoolEntry.mysticArcanumPoolId(spell.level());
            if (!desiredPoolIds.add(poolId)) continue;
            byPoolId.put(poolId, ResourcePoolEntry.mysticArcanum(spell.level(), spell.name().trim())
                    .withCurrentUses(keptUses(byPoolId.get(poolId), 1)));
        }

        for (FeatResourceEntry resource : recordedFeatResources(character)) {
            String featName = resource.featName().trim();
            String poolId = ResourcePoolEntry.featPoolId(featName);
            // A feat taken twice has one pool, with the first's uses
            if (!desiredPoolIds.add(poolId)) continue;

            int uses = Math.max(0, resource.maxUses());
            String name = resource.name() != null && !resource.name().isBlank() ? resource.name().trim() : featName;
            String resetOn = resource.resetOn() != null && !resource.resetOn().isBlank() ? resource.resetOn() : "longRest";
            byPoolId.put(poolId, ResourcePoolEntry.feat(featName, name, uses, resetOn)
                    .withCurrentUses(keptUses(byPoolId.get(poolId), uses)));
        }

        // A magic item's charges (D210): a linked item the character carries with charges, attuned to when it requires
        // attunement (DMG p.136); one pool for each item, however many are carried
        for (Item item : chargedItems(character)) {
            String poolId = ResourcePoolEntry.itemPoolId(item.getName());
            if (!desiredPoolIds.add(poolId)) continue;
            byPoolId.put(poolId, ResourcePoolEntry.magicItem(item.getName(), item.getCharges(), item.getRecharge(),
                    item.getRechargeAmount()).withCurrentUses(keptUses(byPoolId.get(poolId), item.getCharges())));
        }

        // Remove orphaned pools (source no longer present)
        byPoolId.keySet().retainAll(desiredPoolIds);
        return new ArrayList<>(byPoolId.values());
    }

    /**
     * A character with Channel Divinity from two classes, a cleric and a paladin, gains the second class's options but
     * no more uses (PHB p.164), so it keeps one Channel Divinity pool: the one with the most uses at its levels (the
     * cleric's from 6th level), on a tie the one its sheet already has, else the first class's. Every option of
     * either class spends it (ChannelDivinityEffects). The uses already spent from each pool on the sheet stay spent
     * from the one kept, never more than its maximum. A pool is Channel Divinity by its definition's
     * {@code metadata.effect}.
     */
    private static void shareChannelDivinity(PlayerCharacter character, Map<String, ResourcePoolEntry> byPoolId,
                                             Set<String> desiredPoolIds) {
        List<ResourcePoolEntry> pools = desiredPoolIds.stream()
                .map(byPoolId::get)
                .filter(pool -> pool != null && CHANNEL_DIVINITY_EFFECT.equals(effectOf(pool)))
                .toList();
        if (pools.size() < 2) return;
        Map<String, ResourcePoolEntry> onSheet = orEmpty(character.getResourcePools()).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(ResourcePoolEntry::poolId, e -> e, (a, b) -> a));
        ResourcePoolEntry kept = pools.stream()
                .max(Comparator.comparingInt(ResourcePoolEntry::maxUses)
                        .thenComparing(pool -> onSheet.containsKey(pool.poolId())))
                .orElseThrow();
        int spent = pools.stream()
                .map(pool -> onSheet.get(pool.poolId()))
                .filter(Objects::nonNull)
                .mapToInt(old -> Math.max(0, old.maxUses() - old.currentUses()))
                .sum();
        for (ResourcePoolEntry pool : pools) {
            if (pool != kept) {
                desiredPoolIds.remove(pool.poolId());
                byPoolId.remove(pool.poolId());
            }
        }
        byPoolId.put(kept.poolId(), kept.withCurrentUses(Math.max(0, kept.maxUses() - spent)));
    }

    // The use-pool effect of a Channel Divinity pool, a cleric's or a paladin's, as encounter's ChannelDivinityEffects
    // names it
    private static final String CHANNEL_DIVINITY_EFFECT = "channel-divinity";

    private static String effectOf(ResourcePoolEntry pool) {
        return pool.metadata() instanceof Map<?, ?> map && map.get("effect") instanceof String effect ? effect : null;
    }

    // The linked items with charges the character carries, attuned where they require it, in inventory order
    private List<Item> chargedItems(PlayerCharacter character) {
        List<InventoryItem> entries = orEmpty(character.getEquipment()).stream()
                .filter(entry -> entry != null && entry.itemId() != null && entry.carried() > 0)
                .toList();
        if (entries.isEmpty()) return List.of();
        Map<UUID, Item> byId = new HashMap<>();
        for (Item item : itemRepository.findAllById(entries.stream().map(InventoryItem::itemId).distinct().toList())) {
            byId.put(item.getId(), item);
        }
        List<Item> charged = new ArrayList<>();
        for (InventoryItem entry : entries) {
            Item item = byId.get(entry.itemId());
            if (item == null || item.getCharges() == null || item.getCharges() <= 0) continue;
            if (Boolean.TRUE.equals(item.getRequiresAttunement()) && !Boolean.TRUE.equals(entry.attuned())) continue;
            charged.add(item);
        }
        return charged;
    }

    // The row of the definition's level table at the level of the class it names, or null without one
    private static PoolLevelTable.Row levelRow(ResourcePoolDefinition def, PlayerCharacter character) {
        PoolLevelTable table = def.getLevelTable();
        if (table == null || table.levelOf() == null) return null;
        int classLevel = orEmpty(character.getMulticlassEntries()).stream()
                .filter(entry -> table.levelOf().equalsIgnoreCase(entry.className()))
                .mapToInt(ClassEntry::level)
                .sum();
        return table.rowAt(classLevel).orElse(null);
    }

    // A pool the character already has keeps the uses it has spent, as slots and Hit Dice do, so a higher maximum
    // adds uses and a lower one takes them away; a new pool starts full
    private static int keptUses(ResourcePoolEntry existing, int maxUses) {
        if (existing == null) return maxUses;
        int spent = Math.max(0, existing.maxUses() - existing.currentUses());
        return Math.max(0, maxUses - spent);
    }

    // A levelled spell the character can cast now with uses of its own, as SpellSources reads it in combat
    private static boolean hasInnateUses(KnownSpell spell, int characterLevel) {
        return spell.name() != null && !spell.name().isBlank()
                && spell.level() > 0
                && spell.usesPerLongRest() != null && spell.usesPerLongRest() > 0
                && !Boolean.TRUE.equals(spell.atWill())
                && (spell.unlocksAtLevel() == null || spell.unlocksAtLevel() <= characterLevel);
    }

    // The limited uses of the feats the character has taken with their effects applied, oldest first: a race's or
    // background's at creation and an Ability Score Improvement's, as the level history records them (D159), then the
    // DM's grants (D158)
    private static List<FeatResourceEntry> recordedFeatResources(PlayerCharacter character) {
        List<FeatResourceEntry> resources = new ArrayList<>();
        for (AppliedFeatEffects feat : DmAdjustments.featsTaken(character)) {
            FeatResourceEntry resource = feat.resource();
            if (resource != null && resource.featName() != null && !resource.featName().isBlank()) {
                resources.add(resource);
            }
        }
        return resources;
    }

    // RACE or FEAT for a race or feat spell, CLASS for any other a character knows
    private static String sourceType(String source) {
        if (source != null && source.startsWith("race:")) return "RACE";
        if (source != null && source.startsWith("feat:")) return "FEAT";
        return "CLASS";
    }

    private static String sourceName(String source) {
        return source != null && source.contains(":") ? source.substring(source.indexOf(':') + 1) : null;
    }

    // ── Pool mutation (in-combat) ─────────────────────────────────

    /**
     * The pools after spending uses of one, on a character sheet or a participant: one rule for both (D127, D169). An
     * unlimited pool, such as Rage at Barbarian 20, is never refused and keeps its uses. A pool that recovers another,
     * such as a Psi Warrior's Psionic Recovery, gives that one a use back for each it spends.
     *
     * @param owner whose pools they are, for the refusals
     * @throws ValidationException for a pool they don't have
     * @throws ConflictException   when the pool hasn't that many uses left, or the pool it recovers hasn't that many
     *                             spent
     */
    public static List<ResourcePoolEntry> spend(List<ResourcePoolEntry> pools, String poolId, int uses, String owner) {
        List<ResourcePoolEntry> updated = new ArrayList<>(pools != null ? pools : List.of());
        ResourcePoolEntry spent = find(updated, poolId, owner);
        if (spent.unlimited()) return updated;
        if (spent.currentUses() < uses) {
            throw new ConflictException(spent.displayName() + " has " + spent.currentUses() + " of "
                    + spent.maxUses() + " uses left, not " + uses);
        }
        if (spent.recovers() != null) {
            ResourcePoolEntry recovered = find(updated, spent.recovers(), owner);
            if (!recovered.unlimited() && recovered.currentUses() + uses > recovered.maxUses()) {
                throw new ConflictException(recovered.displayName() + " has " + recovered.currentUses() + " of "
                        + recovered.maxUses() + " left, so " + spent.displayName() + " has nothing to regain");
            }
            replace(updated, recovered.withCurrentUses(Math.min(recovered.maxUses(), recovered.currentUses() + uses)));
        }
        replace(updated, spent.withCurrentUses(spent.currentUses() - uses));
        return updated;
    }

    private static ResourcePoolEntry find(List<ResourcePoolEntry> pools, String poolId, String owner) {
        return pools.stream().filter(pool -> pool != null && poolId.equals(pool.poolId())).findFirst()
                .orElseThrow(() -> new ValidationException(owner + " has no pool " + poolId));
    }

    // Replaces the first pool with the same ID
    private static void replace(List<ResourcePoolEntry> pools, ResourcePoolEntry changed) {
        for (int i = 0; i < pools.size(); i++) {
            if (pools.get(i) != null && changed.poolId().equals(pools.get(i).poolId())) {
                pools.set(i, changed);
                return;
            }
        }
    }

    /**
     * Decrements currentUses on the participant's pool. Clamped at 0. An unlimited pool always succeeds and keeps its
     * uses. A pool that recovers another, such as a Psi Warrior's Psionic Recovery, follows the sheet's rule through
     * {@link #spend}: it gives the other pool a use back, and is refused with a 409 when that pool has none spent,
     * so Write-back carries the regained use to the sheet (D166).
     *
     * @return true if the spend succeeded (pool exists and had enough uses)
     * @throws ConflictException when a pool that recovers another can't be spent
     */
    public boolean spendPool(EncounterParticipant participant, String poolId, int amount) {
        ResourcePoolEntry named = participantPools(participant).stream()
                .filter(pool -> pool != null && poolId.equals(pool.poolId())).findFirst().orElse(null);
        if (named != null && named.recovers() != null) {
            participant.setResourcePoolsCurrent(spend(participantPools(participant), poolId, amount,
                    participant.getDisplayName()));
            return true;
        }
        boolean found = false;
        boolean hadEnough = false;
        List<ResourcePoolEntry> updated = new ArrayList<>();

        for (ResourcePoolEntry e : participantPools(participant)) {
            if (poolId.equals(e.poolId()) && e.unlimited()) {
                // An unlimited pool, such as Rage at Barbarian 20, is never refused and never runs down
                found = true;
                hadEnough = true;
                updated.add(e);
            } else if (poolId.equals(e.poolId())) {
                found = true;
                hadEnough = e.currentUses() >= amount;
                int newUses = Math.max(0, e.currentUses() - amount);
                updated.add(e.withCurrentUses(newUses));
            } else {
                updated.add(e);
            }
        }

        participant.setResourcePoolsCurrent(updated);
        return found && hadEnough;
    }

    /**
     * Increments currentUses on the participant's pool. Capped at maxUses.
     *
     * @return true if the pool was found and recovered
     */
    public boolean recoverPool(EncounterParticipant participant, String poolId, int amount) {
        boolean found = false;
        List<ResourcePoolEntry> updated = new ArrayList<>();

        for (ResourcePoolEntry e : participantPools(participant)) {
            if (poolId.equals(e.poolId())) {
                found = true;
                int newUses = Math.min(e.maxUses(), e.currentUses() + amount);
                updated.add(e.withCurrentUses(newUses));
            } else {
                updated.add(e);
            }
        }

        participant.setResourcePoolsCurrent(updated);
        return found;
    }

    // ── Reset logic ──────────────────────────────────────────────

    /**
     * Resets pools matching the given trigger.
     *
     * @param pools        the pool list to mutate (character sheet or participant)
     * @param resetTrigger "shortRest", "longRest", or "turn"
     * @param context      variable context for evaluating resetAmount expressions
     * @return the mutated pool list (convenience)
     */
    public ResetPoolsResult resetPools(List<ResourcePoolEntry> pools, String resetTrigger,
                                              Map<String, Integer> context) {
        List<ResourcePoolEntry> updated = new ArrayList<>();
        List<RechargeLogEntry> rechargeResults = new ArrayList<>();

        for (ResourcePoolEntry e : pools) {
            boolean shouldReset = switch (resetTrigger) {
                case "shortRest" -> "shortRest".equals(e.resetOn());
                // A long rest resets everything but a magic item that never regains its charges (D210)
                case "longRest" -> !ResourcePoolEntry.NO_RESET.equals(e.resetOn());
                case "turn" -> "turn".equals(e.resetOn());
                default -> false;
            };

            if (!shouldReset) {
                // Check probabilistic recharge
                if ("turn".equals(resetTrigger) && e.resetCheck() != null && e.currentUses() == 0) {
                    Expression.RechargeResult recharge = Expression.rechargeCheck(e.resetCheck());
                    if (recharge.success()) {
                        int recoverAmount = resolveResetAmount(e, context);
                        updated.add(e.withCurrentUses(Math.min(e.maxUses(), recoverAmount)));
                        rechargeResults.add(new RechargeLogEntry(
                                e.poolId(), e.displayName(), true, recharge.rollValue(), e.resetCheck()));
                        continue;
                    }
                    rechargeResults.add(new RechargeLogEntry(
                            e.poolId(), e.displayName(), false, recharge.rollValue(), e.resetCheck()));
                }
                updated.add(e);
                continue;
            }

            int newUses;
            if ("longRest".equals(resetTrigger) || "shortRest".equals(resetTrigger)) {
                newUses = resolveResetAmount(e, context);
            } else {
                // Per-turn: full reset to maxUses
                newUses = e.maxUses();
            }

            updated.add(e.withCurrentUses(Math.min(e.maxUses(), Math.max(0, newUses))));
        }

        return new ResetPoolsResult(updated, rechargeResults);
    }

    // ── Internal helpers ─────────────────────────────────────────

    // The entry for a definition, with its level table's row, if one applies, in place of the definition's own rest,
    // die and limit
    private ResourcePoolEntry buildEntry(ResourcePoolDefinition def, PoolLevelTable.Row row, String formula,
                                                int maxUses, int currentUses) {
        Map<String, Object> metadata = definitionMetadata(def);
        String resetOn = def.getResetOn();
        if (row != null) {
            if (row.resetOn() != null) resetOn = row.resetOn();
            if (row.die() != null) metadata.put("die", row.die());
            if (Boolean.TRUE.equals(row.unlimited())) metadata.put("unlimited", true);
        }
        return new ResourcePoolEntry(
                def.getPoolId(),
                def.getDisplayName(),
                def.getSourceType(),
                null, // sourceName filled from context if needed
                maxUses,
                formula,
                currentUses,
                resetOn,
                def.getResetAmount(),
                def.getResetCheck(),
                def.getSpendActionType(),
                def.getIcon(),
                metadata
        );
    }

    // The definition's metadata as a map to add to, empty without any
    private Map<String, Object> definitionMetadata(ResourcePoolDefinition def) {
        if (def.getMetadata() == null || def.getMetadata().isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(def.getMetadata(), new TypeReference<Map<String, Object>>() {}));
        } catch (JacksonException e) {
            throw new IllegalStateException("The " + def.getPoolId() + " pool's metadata isn't a JSON object", e);
        }
    }

    // A definition without a formula is the data's mistake, which the loader refuses: no default stands in (D169)
    private static int evaluateMaxUses(String formula, Map<String, Integer> context, String poolId) {
        if (formula == null || formula.isBlank()) {
            throw new ExpressionException("The " + poolId + " pool has no maximum uses formula");
        }
        return Expression.evaluate(formula, context);
    }

    private int resolveResetAmount(ResourcePoolEntry entry, Map<String, Integer> context) {
        if (entry.resetAmount() == null) {
            return entry.maxUses(); // full reset
        }
        Map<String, Integer> ctx = new LinkedHashMap<>(context);
        ctx.put("maxUses", entry.maxUses());
        ctx.put("currentUses", entry.currentUses());
        return Expression.evaluate(entry.resetAmount(), ctx);
    }

    /**
     * Every name a character's pool formulas can read, for the loader to check them with (D169): the proficiency
     * bonus, the total level, each class's level, each ability modifier, and {@code maxUses} for a reset amount.
     *
     * @param classNames the names of the classes there are, such as "Monk"
     */
    public static Set<String> formulaNames(Collection<String> classNames) {
        Set<String> names = new LinkedHashSet<>(List.of("proficiencyBonus", "totalLevel", "maxUses"));
        classNames.forEach(name -> names.add(classLevelName(name)));
        for (Ability ability : Ability.values()) {
            names.add(ability.fullName() + "Modifier");
        }
        return names;
    }

    // The name a class's level has in a formula, such as monkLevel
    private static String classLevelName(String className) {
        return className.toLowerCase(Locale.ROOT) + "Level";
    }

    public Map<String, Integer> buildCharacterContext(PlayerCharacter character) {
        Map<String, Integer> ctx = new LinkedHashMap<>();
        ctx.put("proficiencyBonus", character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2);
        ctx.put("totalLevel", character.getLevel() != null ? character.getLevel() : 1);

        // Class levels from multiclass entries
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        for (ClassEntry e : entries) {
            ctx.put(classLevelName(e.className()), e.level());
        }

        // Ability modifiers, from the scores with the DM's adjustments (D158)
        for (Ability ability : Ability.values()) {
            ctx.put(ability.fullName() + "Modifier", Ability.modifier(DmAdjustments.abilityScore(character, ability)));
        }

        return ctx;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static List<ResourcePoolEntry> participantPools(EncounterParticipant participant) {
        return participant.getResourcePoolsCurrent() != null ? participant.getResourcePoolsCurrent() : List.of();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
