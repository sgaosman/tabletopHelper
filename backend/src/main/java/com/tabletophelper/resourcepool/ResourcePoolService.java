package com.tabletophelper.resourcepool;

import com.tabletophelper.character.DmAdjustments;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ExpressionEvaluator;
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
 *   <li>copyToParticipant — copies currentUses to encounter participant on join</li>
 *   <li>Spend/recover during combat — tracked independently on participant</li>
 *   <li>syncBackToCharacter — writes currentUses back to character sheet on COMPLETED</li>
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

    // ── Character sheet ──────────────────────────────────────────

    /**
     * The pools a character should have, from the class feature definitions for its classes and subclasses, one
     * {@code innate:<spell>} pool for each spell it can cast now with uses of its own (D165), and one
     * {@code feat:<feat>} pool for each feat with limited uses that its level history records or the DM granted, such
     * as Lucky's (D158). A pool it already has keeps its place and the uses it has spent; one whose source is gone is
     * dropped.
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

                int maxUses = evaluateMaxUses(def.getMaxUsesFormula(), context, def.getPoolId());
                byPoolId.put(def.getPoolId(), buildEntry(def, maxUses, keptUses(byPoolId.get(def.getPoolId()), maxUses)));
            }
        }

        int characterLevel = character.getLevel() != null ? character.getLevel() : 1;
        for (KnownSpell spell : orEmpty(character.getSpellsKnown())) {
            if (!hasInnateUses(spell, characterLevel)) continue;
            String poolId = ResourcePoolEntry.innatePoolId(spell.name());
            // A spell known from two places has one pool, with the first's uses
            if (!desiredPoolIds.add(poolId)) continue;

            int uses = spell.usesPerLongRest();
            byPoolId.put(poolId, ResourcePoolEntry.innate(spell.name().trim(), uses, sourceType(spell.source()),
                    sourceName(spell.source())).withCurrentUses(keptUses(byPoolId.get(poolId), uses)));
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

        // Remove orphaned pools (source no longer present)
        byPoolId.keySet().retainAll(desiredPoolIds);
        return new ArrayList<>(byPoolId.values());
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

    /**
     * Copies currentUses from the character sheet to the encounter participant.
     * Called when a player character joins an encounter.
     */
    public void copyToParticipant(EncounterParticipant participant, PlayerCharacter character) {
        participant.setResourcePoolsCurrent(new ArrayList<>(orEmpty(character.getResourcePools())));
    }

    /**
     * Writes currentUses back from the participant to the character sheet
     * for matching poolIds. Called when an encounter transitions to COMPLETED.
     */
    public void syncBackToCharacter(EncounterParticipant participant, PlayerCharacter character) {
        if (participant.getResourcePoolsCurrent() == null) return;
        List<ResourcePoolEntry> partPools = participant.getResourcePoolsCurrent();
        List<ResourcePoolEntry> charPools = orEmpty(character.getResourcePools());

        // A participant holds one pool per ID; should it hold two, the first counts
        Map<String, Integer> currentByPoolId = partPools.stream()
                .collect(Collectors.toMap(ResourcePoolEntry::poolId, ResourcePoolEntry::currentUses, (a, b) -> a));

        List<ResourcePoolEntry> updated = charPools.stream()
                .map(e -> currentByPoolId.containsKey(e.poolId())
                        ? e.withCurrentUses(currentByPoolId.get(e.poolId()))
                        : e)
                .collect(Collectors.toCollection(ArrayList::new));

        character.setResourcePools(updated);
    }

    // ── Pool mutation (in-combat) ─────────────────────────────────

    /**
     * Decrements currentUses on the participant's pool. Clamped at 0.
     *
     * @return true if the spend succeeded (pool exists and had enough uses)
     */
    public boolean spendPool(EncounterParticipant participant, String poolId, int amount) {
        boolean found = false;
        boolean hadEnough = false;
        List<ResourcePoolEntry> updated = new ArrayList<>();

        for (ResourcePoolEntry e : participantPools(participant)) {
            if (poolId.equals(e.poolId())) {
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
                case "longRest" -> true; // long rest resets everything
                case "turn" -> "turn".equals(e.resetOn());
                default -> false;
            };

            if (!shouldReset) {
                // Check probabilistic recharge
                if ("turn".equals(resetTrigger) && e.resetCheck() != null && e.currentUses() == 0) {
                    ExpressionEvaluator.RechargeResult recharge =
                            ExpressionEvaluator.evaluateRechargeCheckWithRoll(e.resetCheck());
                    if (recharge != null) {
                        if (recharge.success()) {
                            int recoverAmount = resolveResetAmount(e, context);
                            updated.add(e.withCurrentUses(Math.min(e.maxUses(), recoverAmount)));
                            rechargeResults.add(new RechargeLogEntry(
                                    e.poolId(), e.displayName(), true, recharge.rollValue(), e.resetCheck()));
                            continue;
                        } else {
                            rechargeResults.add(new RechargeLogEntry(
                                    e.poolId(), e.displayName(), false, recharge.rollValue(), e.resetCheck()));
                        }
                    }
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

    private ResourcePoolEntry buildEntry(ResourcePoolDefinition def, int maxUses, int currentUses) {
        return new ResourcePoolEntry(
                def.getPoolId(),
                def.getDisplayName(),
                def.getSourceType(),
                null, // sourceName filled from context if needed
                maxUses,
                def.getMaxUsesFormula(),
                currentUses,
                def.getResetOn(),
                def.getResetAmount(),
                def.getResetCheck(),
                def.getSpendActionType(),
                def.getIcon(),
                def.getMetadata() != null ? def.getMetadata() : Map.of()
        );
    }

    private int evaluateMaxUses(String formula, Map<String, Integer> context, String poolId) {
        if (formula == null || formula.isBlank()) {
            // No formula — use a default of 1? Actually, definitions without a formula
            // should have an implicit max that is set during seeding. Return 1 as safe fallback.
            return 1;
        }
        return ExpressionEvaluator.evaluate(formula, context);
    }

    private int resolveResetAmount(ResourcePoolEntry entry, Map<String, Integer> context) {
        if (entry.resetAmount() == null) {
            return entry.maxUses(); // full reset
        }
        Map<String, Integer> ctx = new LinkedHashMap<>(context);
        ctx.put("maxUses", entry.maxUses());
        return ExpressionEvaluator.evaluate(entry.resetAmount(), ctx);
    }

    public Map<String, Integer> buildCharacterContext(PlayerCharacter character) {
        Map<String, Integer> ctx = new LinkedHashMap<>();
        ctx.put("proficiencyBonus", character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2);
        ctx.put("totalLevel", character.getLevel() != null ? character.getLevel() : 1);

        // Class levels from multiclass entries
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        for (ClassEntry e : entries) {
            String key = e.className().toLowerCase() + "Level";
            ctx.put(key, e.level());
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
