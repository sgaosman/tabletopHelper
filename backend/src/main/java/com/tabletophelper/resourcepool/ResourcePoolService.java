package com.tabletophelper.resourcepool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.MulticlassEntry;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ExpressionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of resource pools across character sheets,
 * encounter participants, and rest recovery.
 *
 * <h3>Pool lifecycle</h3>
 * <ol>
 *   <li>syncPoolsForCharacter — creates/reconciles pools on the character sheet</li>
 *   <li>copyToParticipant — copies currentUses to encounter participant on join</li>
 *   <li>Spend/recover during combat — tracked independently on participant</li>
 *   <li>syncBackToCharacter — writes currentUses back to character sheet on COMPLETED</li>
 *   <li>resetPools — applies rest recovery (short/long) or per-turn resets</li>
 * </ol>
 */
@Slf4j
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
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<ResourcePoolEntry>> POOL_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<MulticlassEntry>> MC_ENTRY_LIST_TYPE =
            new TypeReference<>() {};

    // ── Character sheet ──────────────────────────────────────────

    /**
     * Idempotent reconciliation: ensures the character's {@code resource_pools}
     * matches what the definition tables say should exist. Safe to call after
     * creation, level-up, feat application, or any feature change.
     */
    public void syncPoolsForCharacter(PlayerCharacter character) {
        try {
            List<ResourcePoolEntry> existing = parsePools(character.getResourcePools());
            Map<String, ResourcePoolEntry> byPoolId = existing.stream()
                    .collect(Collectors.toMap(ResourcePoolEntry::poolId, e -> e, (a, b) -> a, LinkedHashMap::new));

            Map<String, Integer> context = buildCharacterContext(character);

            // Collect desired pools from class feature definitions
            List<MulticlassEntry> classEntries = parseMulticlassEntries(character.getMulticlassEntries());
            Set<String> desiredPoolIds = new LinkedHashSet<>();

            for (MulticlassEntry entry : classEntries) {
                UUID classId = UUID.fromString(entry.classId());
                List<ClassFeaturePool> featurePools;

                if (entry.subclassId() != null && !entry.subclassId().isBlank()) {
                    UUID subclassId = UUID.fromString(entry.subclassId());
                    featurePools = classFeaturePoolRepository
                            .findByClassIdAndLevelAndSubclass(classId, entry.level(), subclassId);
                } else {
                    featurePools = classFeaturePoolRepository
                            .findByCharacterClassIdAndSubclassIsNullAndMinLevelLessThanEqual(classId, entry.level());
                }

                for (var fp : featurePools) {
                    ResourcePoolDefinition def = fp.getPoolDefinition();
                    if (def == null) continue;
                    desiredPoolIds.add(def.getPoolId());

                    ResourcePoolEntry existingEntry = byPoolId.get(def.getPoolId());
                    int maxUses = evaluateMaxUses(def.getMaxUsesFormula(), context, def.getPoolId());

                    ResourcePoolEntry entry2 = buildEntry(def, maxUses,
                            existingEntry != null ? existingEntry.currentUses() : maxUses);
                    byPoolId.put(def.getPoolId(), entry2);
                }
            }

            // Remove orphaned pools (source no longer present)
            byPoolId.keySet().retainAll(desiredPoolIds);

            List<ResourcePoolEntry> result = new ArrayList<>(byPoolId.values());
            character.setResourcePools(serializePools(result));
        } catch (Exception e) {
            log.error("Failed to sync resource pools for character {}", character.getId(), e);
        }
    }

    /**
     * Copies currentUses from the character sheet to the encounter participant.
     * Called when a player character joins an encounter.
     */
    public void copyToParticipant(EncounterParticipant participant, PlayerCharacter character) {
        participant.setResourcePoolsCurrent(parsePools(character.getResourcePools()));
    }

    /**
     * Writes currentUses back from the participant to the character sheet
     * for matching poolIds. Called when an encounter transitions to COMPLETED.
     */
    public void syncBackToCharacter(EncounterParticipant participant, PlayerCharacter character) {
        try {
            if (participant.getResourcePoolsCurrent() == null) return;
            List<ResourcePoolEntry> partPools = participant.getResourcePoolsCurrent();
            List<ResourcePoolEntry> charPools = parsePools(character.getResourcePools());

            Map<String, Integer> currentByPoolId = partPools.stream()
                    .collect(Collectors.toMap(ResourcePoolEntry::poolId, ResourcePoolEntry::currentUses));

            List<ResourcePoolEntry> updated = charPools.stream()
                    .map(e -> currentByPoolId.containsKey(e.poolId())
                            ? e.withCurrentUses(currentByPoolId.get(e.poolId()))
                            : e)
                    .toList();

            character.setResourcePools(serializePools(updated));
        } catch (Exception e) {
            log.error("Failed to sync pools back to character {}", character.getId(), e);
        }
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
        List<MulticlassEntry> entries = parseMulticlassEntries(character.getMulticlassEntries());
        for (MulticlassEntry e : entries) {
            String key = e.className().toLowerCase() + "Level";
            ctx.put(key, e.level());
        }

        // Ability modifiers
        ctx.put("strengthModifier", abilityMod(character.getStrength()));
        ctx.put("dexterityModifier", abilityMod(character.getDexterity()));
        ctx.put("constitutionModifier", abilityMod(character.getConstitution()));
        ctx.put("intelligenceModifier", abilityMod(character.getIntelligence()));
        ctx.put("wisdomModifier", abilityMod(character.getWisdom()));
        ctx.put("charismaModifier", abilityMod(character.getCharisma()));

        return ctx;
    }

    private int abilityMod(Integer score) {
        if (score == null) return 0;
        return Ability.modifier(score);
    }

    // ── JSON helpers ─────────────────────────────────────────────

    private static List<ResourcePoolEntry> participantPools(EncounterParticipant participant) {
        return participant.getResourcePoolsCurrent() != null ? participant.getResourcePoolsCurrent() : List.of();
    }

    /** Reads a character sheet's pools, which stay a JSON string until R6 types them (D168). */
    public List<ResourcePoolEntry> parsePools(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, POOL_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse resource pools JSON, returning empty", e);
            return new ArrayList<>();
        }
    }

    private String serializePools(List<ResourcePoolEntry> pools) {
        try {
            return objectMapper.writeValueAsString(pools);
        } catch (Exception e) {
            log.error("Failed to serialize resource pools", e);
            return "[]";
        }
    }

    List<MulticlassEntry> parseMulticlassEntries(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, MC_ENTRY_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse multiclass entries, returning empty", e);
            return new ArrayList<>();
        }
    }
}
