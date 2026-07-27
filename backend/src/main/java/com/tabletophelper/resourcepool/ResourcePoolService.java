package com.tabletophelper.resourcepool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.character.dto.MulticlassEntry;
import com.tabletophelper.character.dto.ResourcePoolEntry;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.ClassFeaturePool;
import com.tabletophelper.reference.ClassFeaturePoolRepository;
import com.tabletophelper.reference.MonsterPoolTrigger;
import com.tabletophelper.reference.MonsterPoolTriggerRepository;
import com.tabletophelper.reference.ResourcePoolDefinition;
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
 *   <li>createForMonster — creates fresh pools for monsters on encounter join</li>
 *   <li>Spend/recover during combat — tracked independently on participant</li>
 *   <li>syncBackToCharacter — writes currentUses back to character sheet on COMPLETED</li>
 *   <li>resetPools — applies rest recovery (short/long) or per-turn resets</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourcePoolService {

    private final ClassFeaturePoolRepository classFeaturePoolRepository;
    private final MonsterPoolTriggerRepository monsterPoolTriggerRepository;
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
        try {
            List<ResourcePoolEntry> charPools = parsePools(character.getResourcePools());
            participant.setResourcePoolsCurrent(serializePools(charPools));
        } catch (Exception e) {
            log.error("Failed to copy resource pools to participant {}", participant.getId(), e);
        }
    }

    /**
     * Writes currentUses back from the participant to the character sheet
     * for matching poolIds. Called when an encounter transitions to COMPLETED.
     */
    public void syncBackToCharacter(EncounterParticipant participant, PlayerCharacter character) {
        try {
            if (participant.getResourcePoolsCurrent() == null) return;
            List<ResourcePoolEntry> partPools = parsePools(participant.getResourcePoolsCurrent());
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

    // ── Monster pools ────────────────────────────────────────────

    /**
     * Creates fresh resource pools for a monster based on matching
     * {@link MonsterPoolTrigger} conditions.
     */
    public void createForMonster(EncounterParticipant participant, Monster monster) {
        try {
            List<MonsterPoolTrigger> triggers = monsterPoolTriggerRepository.findAllByOrderByPriorityAsc();
            List<ResourcePoolEntry> pools = new ArrayList<>();

            for (MonsterPoolTrigger trigger : triggers) {
                if (matchesTrigger(trigger, monster)) {
                    ResourcePoolDefinition def = trigger.getPoolDefinition();
                    if (def == null) continue;

                    Map<String, Integer> context = buildMonsterContext(monster);
                    int maxUses = evaluateMaxUses(def.getMaxUsesFormula(), context, def.getPoolId());

                    pools.add(buildEntry(def, maxUses, maxUses));
                }
            }

            participant.setResourcePoolsCurrent(serializePools(pools));
        } catch (Exception e) {
            log.error("Failed to create resource pools for monster participant {}", participant.getId(), e);
        }
    }

    // ── Pool mutation (in-combat) ─────────────────────────────────

    /**
     * Decrements currentUses on the participant's pool. Clamped at 0.
     *
     * @return true if the spend succeeded (pool exists and had enough uses)
     */
    public boolean spendPool(EncounterParticipant participant, String poolId, int amount) {
        try {
            List<ResourcePoolEntry> pools = parsePools(participant.getResourcePoolsCurrent());
            boolean found = false;
            List<ResourcePoolEntry> updated = new ArrayList<>();

            for (ResourcePoolEntry e : pools) {
                if (e.poolId().equals(poolId)) {
                    found = true;
                    int newUses = Math.max(0, e.currentUses() - amount);
                    updated.add(e.withCurrentUses(newUses));
                } else {
                    updated.add(e);
                }
            }

            participant.setResourcePoolsCurrent(serializePools(updated));
            return found;
        } catch (Exception e) {
            log.error("Failed to spend pool {} on participant {}", poolId, participant.getId(), e);
            return false;
        }
    }

    /**
     * Increments currentUses on the participant's pool. Capped at maxUses.
     *
     * @return true if the pool was found and recovered
     */
    public boolean recoverPool(EncounterParticipant participant, String poolId, int amount) {
        try {
            List<ResourcePoolEntry> pools = parsePools(participant.getResourcePoolsCurrent());
            boolean found = false;
            List<ResourcePoolEntry> updated = new ArrayList<>();

            for (ResourcePoolEntry e : pools) {
                if (e.poolId().equals(poolId)) {
                    found = true;
                    int newUses = Math.min(e.maxUses(), e.currentUses() + amount);
                    updated.add(e.withCurrentUses(newUses));
                } else {
                    updated.add(e);
                }
            }

            participant.setResourcePoolsCurrent(serializePools(updated));
            return found;
        } catch (Exception e) {
            log.error("Failed to recover pool {} on participant {}", poolId, participant.getId(), e);
            return false;
        }
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
    public List<ResourcePoolEntry> resetPools(List<ResourcePoolEntry> pools, String resetTrigger,
                                              Map<String, Integer> context) {
        List<ResourcePoolEntry> updated = new ArrayList<>();

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
                    if (ExpressionEvaluator.evaluateRechargeCheck(e.resetCheck())) {
                        int recoverAmount = resolveResetAmount(e, context);
                        updated.add(e.withCurrentUses(Math.min(e.maxUses(), recoverAmount)));
                        continue;
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

        return updated;
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

    private Map<String, Integer> buildMonsterContext(Monster monster) {
        Map<String, Integer> ctx = new LinkedHashMap<>();
        ctx.put("proficiencyBonus", crToProficiencyBonus(monster.getChallengeRating()));
        ctx.put("totalLevel", crToEstimatedLevel(monster.getChallengeRating()));
        ctx.put("strengthModifier", abilityMod(monster.getStrength()));
        ctx.put("dexterityModifier", abilityMod(monster.getDexterity()));
        ctx.put("constitutionModifier", abilityMod(monster.getConstitution()));
        ctx.put("intelligenceModifier", abilityMod(monster.getIntelligence()));
        ctx.put("wisdomModifier", abilityMod(monster.getWisdom()));
        ctx.put("charismaModifier", abilityMod(monster.getCharisma()));
        return ctx;
    }

    private int abilityMod(Integer score) {
        if (score == null) return 0;
        return Math.floorDiv(score - 10, 2);
    }

    private int crToProficiencyBonus(String cr) {
        if (cr == null) return 2;
        try {
            double crVal = parseCr(cr);
            return (int) Math.ceil(crVal / 4.0) + 1;
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private int crToEstimatedLevel(String cr) {
        if (cr == null) return 1;
        try {
            return Math.max(1, (int) Math.ceil(parseCr(cr)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private double parseCr(String cr) {
        return switch (cr) {
            case "0" -> 0.0;
            case "1/8" -> 0.125;
            case "1/4" -> 0.25;
            case "1/2" -> 0.5;
            default -> Double.parseDouble(cr);
        };
    }

    // ── JSON helpers ─────────────────────────────────────────────

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

    // ── Trigger matching ─────────────────────────────────────────

    private boolean matchesTrigger(MonsterPoolTrigger trigger, Monster monster) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = objectMapper.readValue(
                    trigger.getTriggerCondition(), Map.class);
            if (condition == null) return false;

            if (condition.containsKey("hasField")) {
                String field = (String) condition.get("hasField");
                return switch (field) {
                    case "legendary" -> monster.getLegendaryActions() != null
                            && !monster.getLegendaryActions().isBlank()
                            && !"[]".equals(monster.getLegendaryActions().trim());
                    default -> false;
                };
            }

            if (Boolean.TRUE.equals(condition.get("hasRecharge"))) {
                return hasRechargeAbility(monster);
            }

            if (condition.containsKey("hasFeature")) {
                String feature = (String) condition.get("hasFeature");
                return hasFeature(monster, feature);
            }

            return false;
        } catch (Exception e) {
            log.warn("Failed to evaluate monster trigger condition", e);
            return false;
        }
    }

    private boolean hasRechargeAbility(Monster monster) {
        // Check actions JSON for "recharge" key
        String actions = monster.getActions();
        if (actions != null && actions.contains("\"recharge\"")) return true;
        return false;
    }

    private boolean hasFeature(Monster monster, String featureName) {
        // Check traits JSON for the feature name
        String traits = monster.getTraits();
        if (traits != null && traits.toLowerCase().contains(featureName.toLowerCase())) return true;

        // Also check legendary actions
        String la = monster.getLegendaryActions();
        if (la != null && la.toLowerCase().contains("legendary")) return true;

        return false;
    }
}
