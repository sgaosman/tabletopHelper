package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.monster.Monster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonsterActionResolverEngine {

    private final ObjectMapper objectMapper;

    private static final Map<String, String> ABILITY_KEYS = Map.of(
            "str", "strength", "dex", "dexterity", "con", "constitution",
            "int", "intelligence", "wis", "wisdom", "cha", "charisma"
    );

    private static final String LEGENDARY_RESISTANCE_POOL_ID = "monster:legendary-resistance";

    // ── Records ──────────────────────────────────────────────────────────────

    public record MonsterActionResult(
            boolean resolved,
            String description,
            int totalDamage,
            int totalHealing,
            List<TargetResult> targetResults,
            boolean requiresManualResolution,
            String manualResolutionReason,
            List<String> conditionsInflicted,
            boolean legendaryResistanceAvailable,
            int legendaryResistanceRemaining
    ) {}

    public record TargetResult(
            UUID targetId,
            String targetName,
            int damage,
            int healing,
            boolean savedSuccessfully,
            List<String> conditionsApplied,
            String attackOutcome,
            Integer rollValue,
            Integer rollTotal,
            String damageType,
            boolean legendaryResistanceAvailable,
            int legendaryResistanceRemaining
    ) {
        // Convenience constructor for results that don't check legendary resistance
        public TargetResult(UUID targetId, String targetName, int damage, int healing,
                            boolean savedSuccessfully, List<String> conditionsApplied,
                            String attackOutcome, Integer rollValue, Integer rollTotal,
                            String damageType) {
            this(targetId, targetName, damage, healing, savedSuccessfully, conditionsApplied,
                    attackOutcome, rollValue, rollTotal, damageType, false, 0);
        }
    }

    // ── Main Public Method ───────────────────────────────────────────────────

    /**
     * Resolve a monster action against the provided targets.
     * <p>
     * This method does NOT apply damage or conditions to entities — it computes and
     * returns results for the caller (e.g. CombatService) to apply.
     *
     * @param encounter          the encounter context (for target resolution)
     * @param actingMonster      the participant performing the action
     * @param actionTemplate     the specific action JSON object from the action_templates
     * @param fullTemplates      the full action_templates JSON array (for multiattack lookups)
     * @param targetIds          list of target participant UUIDs
     * @param overrideAttackBonus override for the attack bonus; null uses the template value
     * @param overrideSaveDC     override for the save DC; null uses the template value
     * @param advantage          true=advantage, false=disadvantage, null=normal
     * @return a MonsterActionResult describing all outcomes
     */
    public MonsterActionResult resolveAction(
            Encounter encounter,
            EncounterParticipant actingMonster,
            JsonNode actionTemplate,
            JsonNode fullTemplates,
            List<UUID> targetIds,
            Integer overrideAttackBonus,
            Integer overrideSaveDC,
            Boolean advantage
    ) {
        String actionName = actionTemplate.path("name").asText("Unknown Action");
        boolean automatable = actionTemplate.path("automatable").asBoolean(true);

        // ── Eye Rays (Beholder-style) ────────────────────────────────────────
        if (actionTemplate.path("isEyeRays").asBoolean(false)) {
            return resolveEyeRays(encounter, actingMonster, actionTemplate, targetIds, overrideSaveDC);
        }

        // ── Multiattack ─────────────────────────────────────────────────────
        if (actionTemplate.path("isMultiattack").asBoolean(false)) {
            return resolveMultiattack(encounter, actingMonster, actionTemplate, fullTemplates,
                    targetIds, overrideAttackBonus, overrideSaveDC, advantage);
        }

        // ── Non-automatable actions ──────────────────────────────────────────
        if (!automatable) {
            String description = actionTemplate.path("description").asText("Non-automatable action requires DM adjudication.");
            return manualResult(actionName, description);
        }

        String deliveryMethod = actionTemplate.path("deliveryMethod").asText(null);

        // No delivery method and no effects → non-automatable
        JsonNode effects = actionTemplate.get("effects");
        if (deliveryMethod == null && (effects == null || !effects.isArray() || effects.isEmpty())) {
            String description = actionTemplate.path("description").asText(
                    actionName + " requires DM adjudication.");
            return manualResult(actionName, description);
        }

        // Default delivery method for monster melee/ranged attacks
        if (deliveryMethod == null && effects != null && effects.isArray() && !effects.isEmpty()) {
            deliveryMethod = "AUTO_HIT";
        }

        int attackBonus = overrideAttackBonus != null
                ? overrideAttackBonus
                : actionTemplate.path("attackBonus").asInt(0);
        int saveDC = overrideSaveDC != null
                ? overrideSaveDC
                : actionTemplate.path("saveDC").asInt(0);
        boolean halfOnSave = actionTemplate.path("halfOnSave").asBoolean(false);
        String saveAbility = actionTemplate.path("saveAbility").asText(null);

        // Parse effects
        String damageDice = null;
        String damageType = null;
        String healingDice = null;
        List<ConditionEffect> conditions = new ArrayList<>();
        boolean hasNonAutomatedEffect = false;

        if (effects != null && effects.isArray()) {
            for (JsonNode effect : effects) {
                String effectType = effect.path("effectType").asText("OTHER");
                switch (effectType.toUpperCase()) {
                    case "DAMAGE" -> {
                        if (damageDice == null) {
                            damageDice = effect.path("damageDice").asText(null);
                            damageType = effect.path("damageType").asText(null);
                        }
                    }
                    case "HEAL" -> {
                        if (healingDice == null) {
                            healingDice = effect.path("healingDice").asText(null);
                            if (healingDice == null) {
                                healingDice = effect.path("dice").asText(null);
                            }
                        }
                    }
                    case "CONDITION" -> {
                        String conditionName = effect.path("condition").asText(null);
                        if (conditionName != null) {
                            conditions.add(new ConditionEffect(
                                    conditionName,
                                    effect.path("durationRounds").asInt(0),
                                    effect.path("durationText").asText(null),
                                    effect.path("saveToEndEachTurn").asBoolean(false),
                                    effect.path("saveToEndDC").asInt(0),
                                    effect.path("saveToEndAbility").asText(null)
                            ));
                        }
                    }
                    case "BUFF", "DEBUFF", "OTHER" -> hasNonAutomatedEffect = true;
                    default -> hasNonAutomatedEffect = true;
                }
            }
        }

        if (hasNonAutomatedEffect && (damageDice == null && healingDice == null && conditions.isEmpty())) {
            String reason = "Action contains " + (effects != null && effects.isArray()
                    ? effects.get(0).path("effectType").asText("non-automatable") : "non-automatable")
                    + " effect type requiring DM adjudication";
            return manualResult(actionName, reason);
        }

        List<String> conditionsInflicted = conditions.stream()
                .map(ConditionEffect::name)
                .toList();

        // ── Resolve delivery method against targets ─────────────────────────
        List<EncounterParticipant> targets = resolveTargets(encounter, targetIds);
        List<TargetResult> targetResults = new ArrayList<>();
        int totalDamage = 0;
        int totalHealing = 0;
        StringBuilder desc = new StringBuilder();
        desc.append(actingMonster.getDisplayName()).append(" uses ").append(actionName).append(".");

        switch (deliveryMethod.toUpperCase()) {
            case "ATTACK_ROLL" -> {
                String weaponDamageType = damageType;
                for (EncounterParticipant target : targets) {
                    var result = resolveAttackRoll(target, attackBonus, damageDice, weaponDamageType,
                            conditions, advantage);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatAttackResult(result));
                }
            }
            case "SAVING_THROW" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveSavingThrow(target, saveDC, saveAbility, damageDice,
                            damageType, conditions, halfOnSave);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatSaveResult(result, saveAbility));
                }
            }
            case "AUTO_HIT" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveAutoHit(target, damageDice, damageType, conditions);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatAutoHitResult(result));
                }
            }
            case "AUTO_DAMAGE" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveAutoHit(target, damageDice, damageType, List.of());
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatAutoHitResult(result));
                }
            }
            case "NONE" -> {
                desc.append(" Effect requires DM adjudication.");
                return new MonsterActionResult(false, desc.toString(),
                        0, 0, List.of(), true,
                        "Delivery method 'NONE' requires DM adjudication", List.of(), false, 0);
            }
            default -> {
                return manualResult(actionName, "Delivery method '" + deliveryMethod + "' requires DM adjudication");
            }
        }

        // ── Resolve healing (applied to acting monster or targets) ──────────
        if (healingDice != null && !healingDice.isEmpty()) {
            int healing = DiceRoller.roll(healingDice).total();
            totalHealing = healing;
            desc.append(" Heals for ").append(healing).append(" HP.");
        }

        // ── Legendary resistance check for monster targets ──────────────────
        TargetResult lastResult = targetResults.isEmpty() ? null : targetResults.get(targetResults.size() - 1);
        boolean lrAvailable = false;
        int lrRemaining = 0;
        if (lastResult != null) {
            lrAvailable = lastResult.legendaryResistanceAvailable();
            lrRemaining = lastResult.legendaryResistanceRemaining();
        }

        return new MonsterActionResult(true, desc.toString(), totalDamage, totalHealing,
                targetResults, false, null, conditionsInflicted, lrAvailable, lrRemaining);
    }

    // ── Multiattack ──────────────────────────────────────────────────────────

    /**
     * Resolve a multiattack action by resolving each component action sequentially
     * against the same targets and aggregating the results.
     */
    private MonsterActionResult resolveMultiattack(
            Encounter encounter,
            EncounterParticipant actingMonster,
            JsonNode actionTemplate,
            JsonNode fullTemplates,
            List<UUID> targetIds,
            Integer overrideAttackBonus,
            Integer overrideSaveDC,
            Boolean advantage
    ) {
        String actionName = actionTemplate.path("name").asText("Multiattack");
        JsonNode components = actionTemplate.get("multiattackComponents");
        if (components == null || !components.isArray() || components.isEmpty()) {
            return manualResult(actionName, "Multiattack has no components defined");
        }

        // fullTemplates may be the complete definition object (with actions/legendaryActions/lairActions
        // arrays nested inside) or a flat actions array. Search both forms.
        List<JsonNode> searchArrays = new ArrayList<>();
        if (fullTemplates != null) {
            if (fullTemplates.isArray()) {
                searchArrays.add(fullTemplates);
            } else {
                // It's the definition object — search all action arrays within it
                for (String key : List.of("actions", "legendaryActions", "lairActions", "bonusActions", "reactions", "traits")) {
                    JsonNode arr = fullTemplates.get(key);
                    if (arr != null && arr.isArray()) {
                        searchArrays.add(arr);
                    }
                }
            }
        }

        List<TargetResult> allResults = new ArrayList<>();
        int totalDamage = 0;
        int totalHealing = 0;
        List<String> allConditions = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        desc.append(actingMonster.getDisplayName()).append(" uses ").append(actionName).append(".");

        for (JsonNode componentNameNode : components) {
            String componentName = componentNameNode.asText();

            JsonNode componentTemplate = null;
            for (JsonNode arr : searchArrays) {
                componentTemplate = findActionByName(arr, componentName);
                if (componentTemplate != null) break;
            }
            if (componentTemplate == null) {
                desc.append(" Component '").append(componentName).append("' not found.");
                continue;
            }

            // Resolve the component action recursively — pass the fullTemplates again for nested lookups
            MonsterActionResult componentResult = resolveAction(
                    encounter, actingMonster, componentTemplate, fullTemplates,
                    targetIds, overrideAttackBonus, overrideSaveDC, advantage
            );

            allResults.addAll(componentResult.targetResults());
            totalDamage += componentResult.totalDamage();
            totalHealing += componentResult.totalHealing();
            allConditions.addAll(componentResult.conditionsInflicted());

            if (componentResult.requiresManualResolution()) {
                desc.append(" ").append(componentName).append(": ").append(componentResult.manualResolutionReason()).append(".");
            } else if (componentResult.targetResults().isEmpty()) {
                desc.append(" ").append(componentName).append(" resolved.");
            }
            // Individual TargetResult descriptions are embedded in componentResult.description
        }

        boolean lrAvailable = allResults.stream().anyMatch(TargetResult::legendaryResistanceAvailable);
        int lrRemaining = allResults.stream()
                .mapToInt(TargetResult::legendaryResistanceRemaining)
                .max().orElse(0);

        return new MonsterActionResult(true, desc.toString(), totalDamage, totalHealing,
                allResults, false, null, allConditions, lrAvailable, lrRemaining);
    }

    // ── Eye Rays ─────────────────────────────────────────────────────────────

    /**
     * Resolve a Beholder-style Eye Rays action by randomly selecting rayCount rays
     * and resolving each as a SAVING_THROW against the targets.
     * Each ray targets one distinct target from the targetIds list; if more rays than
     * targets, some targets receive multiple rays.
     */
    private MonsterActionResult resolveEyeRays(
            Encounter encounter,
            EncounterParticipant actingMonster,
            JsonNode actionTemplate,
            List<UUID> targetIds,
            Integer overrideSaveDC
    ) {
        String actionName = actionTemplate.path("name").asText("Eye Rays");
        JsonNode rays = actionTemplate.get("rays");
        int rayCount = actionTemplate.path("rayCount").asInt(3);

        if (rays == null || !rays.isArray() || rays.isEmpty()) {
            return manualResult(actionName, "Eye Rays has no rays defined");
        }

        // Randomly select rayCount rays
        List<JsonNode> rayList = new ArrayList<>();
        rays.forEach(rayList::add);

        List<JsonNode> selectedRays = new ArrayList<>();
        if (rayCount >= rayList.size()) {
            selectedRays.addAll(rayList);
        } else {
            List<JsonNode> shuffled = new ArrayList<>(rayList);
            Collections.shuffle(shuffled, ThreadLocalRandom.current());
            for (int i = 0; i < rayCount; i++) {
                selectedRays.add(shuffled.get(i));
            }
        }

        // Build target rotation: if more rays than targets, cycle through targets
        List<EncounterParticipant> targets = resolveTargets(encounter, targetIds);
        if (targets.isEmpty()) {
            return manualResult(actionName, "No valid targets for eye rays");
        }

        List<TargetResult> allResults = new ArrayList<>();
        int totalDamage = 0;
        List<String> allConditions = new ArrayList<>();
        StringBuilder desc = new StringBuilder();
        desc.append(actingMonster.getDisplayName()).append(" uses ").append(actionName).append(".");

        for (int i = 0; i < selectedRays.size(); i++) {
            JsonNode ray = selectedRays.get(i);
            String rayName = ray.path("name").asText("Ray " + (i + 1));
            EncounterParticipant target = targets.get(i % targets.size());

            int raySaveDC = overrideSaveDC != null ? overrideSaveDC : ray.path("saveDC").asInt(0);
            String saveAbility = ray.path("saveAbility").asText("DEX");
            boolean halfOnSave = ray.path("halfOnSave").asBoolean(false);

            // Parse ray effects
            JsonNode rayEffects = ray.get("effects");
            String damageDice = null;
            String damageType = null;
            List<ConditionEffect> rayConditions = new ArrayList<>();

            if (rayEffects != null && rayEffects.isArray()) {
                for (JsonNode effect : rayEffects) {
                    String effectType = effect.path("effectType").asText("OTHER");
                    switch (effectType.toUpperCase()) {
                        case "DAMAGE" -> {
                            damageDice = effect.path("damageDice").asText(null);
                            damageType = effect.path("damageType").asText(null);
                        }
                        case "CONDITION" -> {
                            String condName = effect.path("condition").asText(null);
                            if (condName != null) {
                                rayConditions.add(new ConditionEffect(
                                        condName,
                                        effect.path("durationRounds").asInt(0),
                                        effect.path("durationText").asText(null),
                                        effect.path("saveToEndEachTurn").asBoolean(false),
                                        effect.path("saveToEndDC").asInt(0),
                                        effect.path("saveToEndAbility").asText(null)
                                ));
                            }
                        }
                        case "HEAL" -> {
                            // Healing in eye rays heals the target (or the monster) — skip for now
                        }
                        default -> {
                            // Unknown effect type, still attempt resolution
                        }
                    }
                }
            }

            // Apply legendary resistance check for the target after save
            TargetResult rayResult = resolveSavingThrow(target, raySaveDC, saveAbility,
                    damageDice, damageType, rayConditions, halfOnSave);

            allResults.add(rayResult);
            totalDamage += rayResult.damage();
            allConditions.addAll(rayResult.conditionsApplied());

            desc.append(" ").append(rayName).append(": ");
            desc.append(formatSaveResult(rayResult, saveAbility));
        }

        boolean lrAvailable = allResults.stream().anyMatch(TargetResult::legendaryResistanceAvailable);
        int lrRemaining = allResults.stream()
                .mapToInt(TargetResult::legendaryResistanceRemaining)
                .max().orElse(0);

        return new MonsterActionResult(true, desc.toString(), totalDamage, 0,
                allResults, false, null, allConditions, lrAvailable, lrRemaining);
    }

    // ── Delivery Method Resolvers ────────────────────────────────────────────

    private TargetResult resolveAttackRoll(
            EncounterParticipant target,
            int attackBonus,
            String damageDice,
            String damageType,
            List<ConditionEffect> conditions,
            Boolean advantage
    ) {
        int roll1 = ThreadLocalRandom.current().nextInt(1, 21);
        int roll2 = ThreadLocalRandom.current().nextInt(1, 21);
        int roll;
        if (Boolean.TRUE.equals(advantage)) {
            roll = Math.max(roll1, roll2);
        } else if (Boolean.FALSE.equals(advantage)) {
            roll = Math.min(roll1, roll2);
        } else {
            roll = roll1;
        }

        int total = roll + attackBonus;
        boolean isNat20 = roll == 20;
        boolean isNat1 = roll == 1;

        if (isNat1) {
            return new TargetResult(target.getId(), target.getDisplayName(),
                    0, 0, false, List.of(), "MISS", roll, total, null);
        }

        boolean targetDowned = !target.getIsAlive() ||
                (target.getHpCurrent() != null && target.getHpCurrent() <= 0);

        if (isNat20 || total >= target.getArmourClass() || targetDowned) {
            int damage = 0;
            if (damageDice != null) {
                if (isNat20) {
                    damage = DiceRoller.rollCritical(damageDice).total();
                } else {
                    damage = DiceRoller.roll(damageDice).total();
                }
            }
            String outcome = isNat20 ? "CRITICAL" : "HIT";
            List<String> conditionNames = conditions.stream().map(ConditionEffect::name).toList();
            return new TargetResult(target.getId(), target.getDisplayName(),
                    damage, 0, false, conditionNames, outcome, roll, total, damageType);
        }

        return new TargetResult(target.getId(), target.getDisplayName(),
                0, 0, false, List.of(), "MISS", roll, total, null);
    }

    private TargetResult resolveSavingThrow(
            EncounterParticipant target,
            int saveDC,
            String saveAbility,
            String damageDice,
            String damageType,
            List<ConditionEffect> conditions,
            boolean halfOnSave
    ) {
        int saveMod = getSaveModifier(target, saveAbility);
        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        int total = roll + saveMod;
        boolean saved = total >= saveDC;

        int damage = 0;
        if (damageDice != null) {
            damage = DiceRoller.roll(damageDice).total();
            if (saved && halfOnSave) {
                damage = Math.max(1, damage / 2);
            } else if (saved) {
                damage = 0;
            }
        }

        List<String> appliedConditions = saved ? List.of() : conditions.stream()
                .map(ConditionEffect::name).toList();

        // ── Legendary resistance check for monster targets ──────────────────
        boolean lrAvailable = false;
        int lrRemaining = 0;

        if (!saved && target.getParticipantType() == ParticipantType.MONSTER) {
            lrRemaining = getLegendaryResistanceRemaining(target);
            lrAvailable = lrRemaining > 0;
            // Do NOT apply legendary resistance here — the caller decides
        }

        return new TargetResult(target.getId(), target.getDisplayName(),
                damage, 0, saved, appliedConditions, saved ? "SAVED" : "FAILED_SAVE",
                roll, total, damageType, lrAvailable, lrRemaining);
    }

    private TargetResult resolveAutoHit(
            EncounterParticipant target,
            String damageDice,
            String damageType,
            List<ConditionEffect> conditions
    ) {
        int damage = 0;
        if (damageDice != null) {
            damage = DiceRoller.roll(damageDice).total();
        }
        List<String> conditionNames = conditions.stream().map(ConditionEffect::name).toList();
        return new TargetResult(target.getId(), target.getDisplayName(),
                damage, 0, false, conditionNames, "HIT", null, null, damageType);
    }

    // ── Helper Methods ───────────────────────────────────────────────────────

    private List<EncounterParticipant> resolveTargets(Encounter encounter, List<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return List.of();
        List<EncounterParticipant> targets = new ArrayList<>();
        for (UUID targetId : targetIds) {
            encounter.getParticipants().stream()
                    .filter(p -> p.getId().equals(targetId))
                    .findFirst()
                    .ifPresent(targets::add);
        }
        return targets;
    }

    private JsonNode findActionByName(JsonNode actionsArray, String name) {
        if (actionsArray == null || !actionsArray.isArray()) return null;
        for (JsonNode action : actionsArray) {
            if (action.path("name").asText("").equalsIgnoreCase(name)) {
                return action;
            }
        }
        return null;
    }

    /**
     * Parse the participant's resourcePoolsCurrent JSON to find remaining
     * uses of the legendary resistance pool.
     */
    private int getLegendaryResistanceRemaining(EncounterParticipant participant) {
        String poolsJson = participant.getResourcePoolsCurrent();
        if (poolsJson == null || poolsJson.isBlank()) return 0;

        try {
            JsonNode pools = objectMapper.readTree(poolsJson);
            if (pools.isArray()) {
                for (JsonNode pool : pools) {
                    String poolId = pool.path("poolId").asText("");
                    if (LEGENDARY_RESISTANCE_POOL_ID.equals(poolId)) {
                        return pool.path("currentUses").asInt(0);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse resourcePoolsCurrent for legendary resistance check: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Get the saving throw modifier for a participant against a given ability.
     * Mirrors SpellResolverEngine.getSaveModifier patterns.
     */
    private int getSaveModifier(EncounterParticipant target, String saveAbility) {
        if (saveAbility == null) return 0;
        String abilityLower = saveAbility.toLowerCase().substring(0, 3);
        String abilityFull = ABILITY_KEYS.getOrDefault(abilityLower, abilityLower);

        if (target.getParticipantType() == ParticipantType.PLAYER && target.getCharacter() != null) {
            PlayerCharacter character = target.getCharacter();
            int score = getAbilityScore(character, abilityFull);
            int mod = Math.floorDiv(score - 10, 2);
            int profBonus = 0;
            if (hasSaveProficiency(character, saveAbility)) {
                profBonus = character.getProficiencyBonus() != null ? character.getProficiencyBonus() : 2;
            }
            return mod + profBonus;
        }

        if (target.getParticipantType() == ParticipantType.MONSTER && target.getMonster() != null) {
            Monster monster = target.getMonster();
            int score = getMonsterAbilityScore(monster, abilityFull);
            int mod = Math.floorDiv(score - 10, 2);

            String savesJson = monster.getSavingThrows();
            if (savesJson != null) {
                try {
                    JsonNode saves = objectMapper.readTree(savesJson);
                    String key = saveAbility.toLowerCase().substring(0, 3);
                    if (saves.has(key)) {
                        return saves.get(key).asInt();
                    }
                } catch (JsonProcessingException e) {
                    // fall through to base mod
                }
            }
            return mod;
        }

        return 0;
    }

    private int getAbilityScore(PlayerCharacter character, String ability) {
        return switch (ability) {
            case "strength" -> character.getStrength() != null ? character.getStrength() : 10;
            case "dexterity" -> character.getDexterity() != null ? character.getDexterity() : 10;
            case "constitution" -> character.getConstitution() != null ? character.getConstitution() : 10;
            case "intelligence" -> character.getIntelligence() != null ? character.getIntelligence() : 10;
            case "wisdom" -> character.getWisdom() != null ? character.getWisdom() : 10;
            case "charisma" -> character.getCharisma() != null ? character.getCharisma() : 10;
            default -> 10;
        };
    }

    private int getMonsterAbilityScore(Monster monster, String ability) {
        return switch (ability) {
            case "strength" -> monster.getStrength() != null ? monster.getStrength() : 10;
            case "dexterity" -> monster.getDexterity() != null ? monster.getDexterity() : 10;
            case "constitution" -> monster.getConstitution() != null ? monster.getConstitution() : 10;
            case "intelligence" -> monster.getIntelligence() != null ? monster.getIntelligence() : 10;
            case "wisdom" -> monster.getWisdom() != null ? monster.getWisdom() : 10;
            case "charisma" -> monster.getCharisma() != null ? monster.getCharisma() : 10;
            default -> 10;
        };
    }

    private boolean hasSaveProficiency(PlayerCharacter character, String ability) {
        String savesJson = character.getSavingThrowProficiencies();
        if (savesJson == null || savesJson.isBlank()) return false;
        try {
            JsonNode saves = objectMapper.readTree(savesJson);
            if (saves.isArray()) {
                String abilityUpper = ability.toUpperCase().substring(0, 3);
                for (JsonNode s : saves) {
                    String val = s.asText();
                    if (val.equalsIgnoreCase(abilityUpper)
                            || val.equalsIgnoreCase(ability)) {
                        return true;
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // ignore
        }
        return false;
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private MonsterActionResult manualResult(String actionName, String reason) {
        return new MonsterActionResult(false,
                actionName + " — " + reason,
                0, 0, List.of(), true, reason, List.of(), false, 0);
    }

    private String formatAttackResult(TargetResult r) {
        if ("MISS".equals(r.attackOutcome())) {
            return "Misses " + r.targetName() + " (" + r.rollValue() + "+" + (r.rollTotal() - r.rollValue()) + "=" + r.rollTotal() + " vs AC).";
        }
        String prefix = "CRITICAL".equals(r.attackOutcome()) ? "Critical hit on " : "Hits ";
        return prefix + r.targetName() + " (" + r.rollValue() + "+" + (r.rollTotal() - r.rollValue()) + "=" + r.rollTotal() + ") for " + r.damage() + " damage.";
    }

    private String formatSaveResult(TargetResult r, String saveAbility) {
        String saveStr = (saveAbility != null ? saveAbility.toUpperCase().substring(0, 3) : "???") + " save";
        if (r.savedSuccessfully()) {
            String dmgPart = r.damage() > 0 ? " for " + r.damage() + " damage (half)." : " — no effect.";
            return r.targetName() + " " + saveStr + " (" + r.rollValue() + "+" + (r.rollTotal() - r.rollValue()) + "=" + r.rollTotal() + ") succeeds" + dmgPart;
        }
        String dmgPart = r.damage() > 0 ? " for " + r.damage() + " damage." : ".";
        return r.targetName() + " " + saveStr + " (" + r.rollValue() + "+" + (r.rollTotal() - r.rollValue()) + "=" + r.rollTotal() + ") fails" + dmgPart;
    }

    private String formatAutoHitResult(TargetResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.targetName());
        if (r.damage() > 0) {
            sb.append(" takes ").append(r.damage()).append(" damage");
            if (r.damageType() != null) {
                sb.append(" (").append(r.damageType()).append(")");
            }
            sb.append(".");
        }
        if (r.conditionsApplied() != null && !r.conditionsApplied().isEmpty()) {
            if (r.damage() > 0) sb.append(" Also");
            sb.append(" affected by: ").append(String.join(", ", r.conditionsApplied())).append(".");
        }
        if (r.damage() <= 0 && (r.conditionsApplied() == null || r.conditionsApplied().isEmpty())) {
            sb.append(" affected.");
        }
        return sb.toString();
    }

    /**
     * Internal record for parsed condition effects with save-to-end details.
     */
    private record ConditionEffect(
            String name,
            int durationRounds,
            String durationText,
            boolean saveToEndEachTurn,
            int saveToEndDC,
            String saveToEndAbility
    ) {}
}
