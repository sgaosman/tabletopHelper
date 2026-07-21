package com.tabletophelper.encounter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.PlayerCharacter;
import com.tabletophelper.monster.Monster;
import com.tabletophelper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpellResolverEngine {

    private final SpellRepository spellRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> ABILITY_KEYS = Map.of(
            "str", "strength", "dex", "dexterity", "con", "constitution",
            "int", "intelligence", "wis", "wisdom", "cha", "charisma"
    );

    public record SpellCastResult(
            boolean resolved,
            String description,
            int totalDamage,
            int totalHealing,
            boolean concentrationSet,
            String concentrationSpellName,
            Integer durationRounds,
            List<TargetResult> targetResults,
            boolean requiresManualResolution,
            String manualResolutionReason,
            List<String> conditionsInflicted
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
            String damageType
    ) {}

    public SpellCastResult resolveSpell(
            Encounter encounter,
            EncounterParticipant caster,
            String spellName,
            int slotLevel,
            List<UUID> targetIds,
            int spellAttackBonus,
            int spellSaveDC,
            Boolean advantage
    ) {
        var spellOpt = spellRepository.findByNameIgnoreCase(spellName);
        if (spellOpt.isEmpty() || spellOpt.get().getEffectTemplate() == null) {
            return manualResult(spellName, "No effect template available");
        }

        JsonNode template;
        try {
            template = objectMapper.readTree(spellOpt.get().getEffectTemplate());
        } catch (JsonProcessingException e) {
            return manualResult(spellName, "Failed to parse effect template");
        }

        if (template.path("requiresManualResolution").asBoolean(false)) {
            String reason = template.path("manualResolutionReason").asText("Complex spell requires DM adjudication");
            boolean conc = template.path("concentration").asBoolean(false);
            Integer durRounds = template.has("durationRounds") && !template.get("durationRounds").isNull()
                    ? template.get("durationRounds").asInt() : null;
            return new SpellCastResult(false,
                    spellName + " cast — " + reason,
                    0, 0, conc, conc ? spellName : null, durRounds,
                    List.of(), true, reason, List.of());
        }

        checkSilence(caster, template);

        String deliveryMethod = template.path("deliveryMethod").asText("NONE");
        boolean concentration = template.path("concentration").asBoolean(false);
        Integer durationRounds = template.has("durationRounds") && !template.get("durationRounds").isNull()
                ? template.get("durationRounds").asInt() : null;
        int spellLevel = template.path("spellLevel").asInt(0);

        JsonNode effects = template.get("effects");
        JsonNode damageEffect = findDamageEffect(effects);
        String damageDice = resolveDamageDice(damageEffect, spellLevel, slotLevel, caster, spellAttackBonus);
        String damageType = damageEffect != null ? damageEffect.path("damageType").asText(null) : null;

        JsonNode conditionsNode = template.get("conditionsInflicted");
        List<String> conditionsInflicted = new ArrayList<>();
        if (conditionsNode != null && conditionsNode.isArray()) {
            for (JsonNode c : conditionsNode) {
                conditionsInflicted.add(c.asText());
            }
        }

        boolean halfOnSave = template.path("halfOnSave").asBoolean(false);
        String saveAbility = template.path("saveAbility").asText(null);

        List<EncounterParticipant> targets = resolveTargets(encounter, targetIds, deliveryMethod, caster);
        List<TargetResult> targetResults = new ArrayList<>();
        int totalDamage = 0;
        int totalHealing = 0;
        StringBuilder desc = new StringBuilder();
        desc.append(caster.getDisplayName()).append(" casts ").append(spellName);
        if (slotLevel > 0 && slotLevel > spellLevel) {
            desc.append(" at level ").append(slotLevel);
        }
        desc.append(".");

        switch (deliveryMethod) {
            case "SPELL_ATTACK" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveSpellAttack(target, spellAttackBonus, damageDice, damageType,
                            conditionsInflicted, advantage);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatAttackResult(result));
                }
            }
            case "SAVING_THROW" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveSavingThrow(target, spellSaveDC, saveAbility, damageDice,
                            damageType, conditionsInflicted, halfOnSave);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatSaveResult(result, saveAbility));
                }
            }
            case "AUTO_HIT" -> {
                for (EncounterParticipant target : targets) {
                    var result = resolveAutoHit(target, damageDice, damageType, conditionsInflicted);
                    targetResults.add(result);
                    totalDamage += result.damage();
                    desc.append(" ").append(formatAutoHitResult(result));
                }
            }
            case "SELF" -> {
                JsonNode healingNode = template.get("healing");
                int healing = resolveHealing(healingNode, spellLevel, slotLevel, caster, spellAttackBonus);
                if (healing > 0) {
                    totalHealing = healing;
                    targetResults.add(new TargetResult(caster.getId(), caster.getDisplayName(),
                            0, healing, false, conditionsInflicted, "self", null, null, null));
                    desc.append(" Heals ").append(healing).append(" HP.");
                } else {
                    targetResults.add(new TargetResult(caster.getId(), caster.getDisplayName(),
                            0, 0, false, conditionsInflicted, "self", null, null, null));
                    desc.append(" Effect applied.");
                }
            }
            case "NONE" -> {
                desc.append(" Effect requires DM adjudication.");
                return new SpellCastResult(false, desc.toString(),
                        0, 0, concentration, concentration ? spellName : null, durationRounds,
                        List.of(), true, "Delivery method 'NONE' requires DM adjudication", List.of());
            }
            default -> {
                return manualResult(spellName, "Delivery method '" + deliveryMethod + "' requires DM adjudication");
            }
        }

        // Handle healing spells that target others (HEAL pattern with non-SELF delivery)
        JsonNode healingNode = template.get("healing");
        if (healingNode != null && !"SELF".equals(deliveryMethod)) {
            int healing = resolveHealing(healingNode, spellLevel, slotLevel, caster, spellAttackBonus);
            if (healing > 0) {
                totalHealing = healing;
                for (int i = 0; i < targetResults.size(); i++) {
                    TargetResult tr = targetResults.get(i);
                    targetResults.set(i, new TargetResult(tr.targetId(), tr.targetName(),
                            tr.damage(), healing, tr.savedSuccessfully(), tr.conditionsApplied(),
                            tr.attackOutcome(), tr.rollValue(), tr.rollTotal(), tr.damageType()));
                }
                desc.append(" Heals ").append(healing).append(" HP.");
            }
        }

        return new SpellCastResult(true, desc.toString(), totalDamage, totalHealing,
                concentration, concentration ? spellName : null, durationRounds,
                targetResults, false, null, conditionsInflicted);
    }

    public SpellCastResult resolveRepeatEffect(
            Encounter encounter,
            EncounterParticipant caster,
            String spellName,
            int slotLevel,
            List<UUID> targetIds,
            int spellAttackBonus,
            int spellSaveDC,
            Boolean advantage
    ) {
        var spellOpt = spellRepository.findByNameIgnoreCase(spellName);
        if (spellOpt.isEmpty() || spellOpt.get().getEffectTemplate() == null) {
            return manualResult(spellName, "No effect template available");
        }

        JsonNode template;
        try {
            template = objectMapper.readTree(spellOpt.get().getEffectTemplate());
        } catch (JsonProcessingException e) {
            return manualResult(spellName, "Failed to parse effect template");
        }

        JsonNode repeatNode = template.get("repeatEffect");
        if (repeatNode == null || repeatNode.isNull()) {
            return manualResult(spellName, "Spell has no repeatable effect");
        }

        String deliveryMethod;
        String saveAbility;
        boolean halfOnSave;
        String damageDice;
        String damageType;
        boolean usesUpcastScaling;

        if (repeatNode.isBoolean() && repeatNode.asBoolean()) {
            deliveryMethod = template.path("deliveryMethod").asText("NONE");
            saveAbility = template.path("saveAbility").asText(null);
            halfOnSave = template.path("halfOnSave").asBoolean(false);
            JsonNode damageEffect = findDamageEffect(template.get("effects"));
            int spellLevel = template.path("spellLevel").asInt(0);
            damageDice = resolveDamageDice(damageEffect, spellLevel, slotLevel, caster, spellAttackBonus);
            damageType = damageEffect != null ? damageEffect.path("damageType").asText(null) : null;
            usesUpcastScaling = true;
        } else {
            deliveryMethod = repeatNode.path("deliveryMethod").asText(template.path("deliveryMethod").asText("NONE"));
            saveAbility = repeatNode.path("saveAbility").asText(template.path("saveAbility").asText(null));
            halfOnSave = repeatNode.path("halfOnSave").asBoolean(template.path("halfOnSave").asBoolean(false));
            damageDice = repeatNode.path("damageDice").asText(null);
            damageType = repeatNode.path("damageType").asText(null);
            usesUpcastScaling = repeatNode.path("usesUpcastScaling").asBoolean(false);
            if (usesUpcastScaling && damageDice != null) {
                int spellLevel = template.path("spellLevel").asInt(0);
                JsonNode upcastScaling = repeatNode.get("upcastScaling");
                if (upcastScaling == null) {
                    JsonNode damageEffect = findDamageEffect(template.get("effects"));
                    upcastScaling = damageEffect != null ? damageEffect.get("upcastScaling") : null;
                }
                if (slotLevel > spellLevel && upcastScaling != null) {
                    damageDice = scaleUpcastDice(damageDice, upcastScaling, slotLevel - spellLevel);
                }
            }
        }

        damageDice = substituteModPlaceholders(damageDice, caster, spellAttackBonus);

        String healingDice = null;
        if (!repeatNode.isBoolean()) {
            healingDice = repeatNode.path("healingDice").asText(null);
        } else {
            JsonNode healingNode = template.get("healing");
            if (healingNode != null) {
                healingDice = healingNode.path("healingDice").asText(healingNode.path("dice").asText(null));
            }
        }
        healingDice = substituteModPlaceholders(healingDice, caster, spellAttackBonus);

        List<String> conditionsInflicted = new ArrayList<>();
        JsonNode conditionsNode = template.get("conditionsInflicted");
        if (conditionsNode != null && conditionsNode.isArray()) {
            for (JsonNode c : conditionsNode) conditionsInflicted.add(c.asText());
        }

        List<EncounterParticipant> targets = resolveTargets(encounter, targetIds, deliveryMethod, caster);
        List<TargetResult> targetResults = new ArrayList<>();
        int totalDamage = 0;
        int totalHealing = 0;
        StringBuilder desc = new StringBuilder();
        desc.append(caster.getDisplayName()).append(" uses ").append(spellName).append(" effect.");

        if (healingDice != null && damageDice == null) {
            int healing = DiceRoller.roll(healingDice).total();
            totalHealing = healing;
            for (EncounterParticipant target : targets) {
                targetResults.add(new TargetResult(target.getId(), target.getDisplayName(),
                        0, healing, false, List.of(), "healed", null, null, null));
                desc.append(" Heals ").append(target.getDisplayName()).append(" for ").append(healing).append(" HP.");
            }
        } else {
            switch (deliveryMethod) {
                case "SPELL_ATTACK" -> {
                    for (EncounterParticipant target : targets) {
                        var result = resolveSpellAttack(target, spellAttackBonus, damageDice, damageType,
                                conditionsInflicted, advantage);
                        targetResults.add(result);
                        totalDamage += result.damage();
                        desc.append(" ").append(formatAttackResult(result));
                    }
                }
                case "SAVING_THROW" -> {
                    for (EncounterParticipant target : targets) {
                        var result = resolveSavingThrow(target, spellSaveDC, saveAbility, damageDice,
                                damageType, conditionsInflicted, halfOnSave);
                        targetResults.add(result);
                        totalDamage += result.damage();
                        desc.append(" ").append(formatSaveResult(result, saveAbility));
                    }
                }
                case "AUTO_HIT" -> {
                    for (EncounterParticipant target : targets) {
                        var result = resolveAutoHit(target, damageDice, damageType, conditionsInflicted);
                        targetResults.add(result);
                        totalDamage += result.damage();
                        desc.append(" ").append(formatAutoHitResult(result));
                    }
                }
                default -> {
                    return manualResult(spellName, "Repeat delivery method '" + deliveryMethod + "' requires DM adjudication");
                }
            }
        }

        return new SpellCastResult(true, desc.toString(), totalDamage, totalHealing,
                false, null, null, targetResults, false, null, conditionsInflicted);
    }

    private void checkSilence(EncounterParticipant caster, JsonNode template) {
        JsonNode components = template.get("components");
        if (components == null || !components.path("verbal").asBoolean(false)) return;

        String conditions = caster.getActiveConditions();
        if (conditions == null) return;

        try {
            JsonNode condArray = objectMapper.readTree(conditions);
            if (condArray.isArray()) {
                for (JsonNode c : condArray) {
                    String name = c.isTextual() ? c.asText() : c.path("name").asText("");
                    if ("silenced".equalsIgnoreCase(name) || "silence".equalsIgnoreCase(name)) {
                        throw new IllegalArgumentException("Cannot cast spells with verbal components while silenced");
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // ignore parse errors
        }
    }

    private JsonNode findDamageEffect(JsonNode effects) {
        if (effects == null || !effects.isArray()) return null;
        for (JsonNode effect : effects) {
            if (effect.has("damageDice")) return effect;
        }
        return null;
    }

    private String resolveDamageDice(JsonNode damageEffect, int spellLevel, int slotLevel,
                                     EncounterParticipant caster, int spellAttackBonus) {
        if (damageEffect == null) return null;

        String baseDice = damageEffect.path("damageDice").asText(null);
        if (baseDice == null) return null;

        baseDice = substituteModPlaceholders(baseDice, caster, spellAttackBonus);

        String result;
        if (spellLevel == 0) {
            result = scaleCantripDice(damageEffect, caster);
            result = substituteModPlaceholders(result, caster, spellAttackBonus);
        } else if (slotLevel > spellLevel) {
            result = scaleUpcastDice(baseDice, damageEffect.get("upcastScaling"), slotLevel - spellLevel);
        } else {
            result = baseDice;
        }

        return result;
    }

    private String substituteModPlaceholders(String dice, EncounterParticipant caster, int spellAttackBonus) {
        if (dice == null) return null;
        if (dice.contains("SPELL_MOD") || dice.contains("MOD")) {
            int charLevel = getCharacterLevel(caster);
            int profBonus = (charLevel - 1) / 4 + 2;
            int spellMod = spellAttackBonus - profBonus;
            dice = dice.replace("SPELL_MOD", String.valueOf(spellMod));
            dice = dice.replace("MOD", String.valueOf(spellMod));
        }
        return dice;
    }

    private String scaleCantripDice(JsonNode effect, EncounterParticipant caster) {
        JsonNode scaling = effect.get("cantripScaling");
        if (scaling == null || !scaling.isObject()) {
            return effect.path("damageDice").asText(null);
        }

        int characterLevel = getCharacterLevel(caster);

        String result = effect.path("damageDice").asText(null);
        int[] thresholds = {17, 11, 5};
        for (int threshold : thresholds) {
            String key = String.valueOf(threshold);
            if (characterLevel >= threshold && scaling.has(key)) {
                result = scaling.get(key).asText();
                break;
            }
        }
        return result;
    }

    private String scaleUpcastDice(String baseDice, JsonNode upcastScaling, int levelsAbove) {
        if (upcastScaling == null || levelsAbove <= 0) return baseDice;

        int scalingInterval = upcastScaling.path("scalingInterval").asInt(1);
        int effectiveLevels = levelsAbove / Math.max(1, scalingInterval);
        if (effectiveLevels <= 0) return baseDice;

        String additionalDicePerLevel = upcastScaling.path("additionalDicePerLevel").asText(null);
        if (additionalDicePerLevel != null) {
            var baseParsed = parseDiceExpression(baseDice);
            var additionalParsed = parseDiceExpression(additionalDicePerLevel);
            if (baseParsed != null && additionalParsed != null && baseParsed[1] == additionalParsed[1]) {
                int totalCount = baseParsed[0] + (additionalParsed[0] * effectiveLevels);
                String result = totalCount + "d" + baseParsed[1];
                if (baseParsed[2] > 0) result += "+" + baseParsed[2];
                return result;
            }
        }

        return baseDice;
    }

    private int[] parseDiceExpression(String expr) {
        if (expr == null) return null;
        try {
            String[] parts = expr.toLowerCase().split("d");
            int count = Integer.parseInt(parts[0]);
            int modifier = 0;
            int sides;
            if (parts[1].contains("+")) {
                String[] sideMod = parts[1].split("\\+");
                sides = Integer.parseInt(sideMod[0].trim());
                modifier = Integer.parseInt(sideMod[1].trim());
            } else {
                sides = Integer.parseInt(parts[1].trim());
            }
            return new int[]{count, sides, modifier};
        } catch (Exception e) {
            return null;
        }
    }

    private int getCharacterLevel(EncounterParticipant participant) {
        if (participant.getParticipantType() == ParticipantType.PLAYER && participant.getCharacter() != null) {
            return participant.getCharacter().getLevel();
        }
        if (participant.getParticipantType() == ParticipantType.MONSTER && participant.getMonster() != null) {
            String crStr = participant.getMonster().getChallengeRating();
            if (crStr != null) {
                try {
                    double cr = crStr.contains("/")
                            ? 1.0 / Double.parseDouble(crStr.split("/")[1])
                            : Double.parseDouble(crStr);
                    return Math.max(1, (int) Math.ceil(cr));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    private List<EncounterParticipant> resolveTargets(Encounter encounter, List<UUID> targetIds,
                                                       String deliveryMethod, EncounterParticipant caster) {
        if ("SELF".equals(deliveryMethod)) {
            return List.of(caster);
        }

        List<EncounterParticipant> targets = new ArrayList<>();
        for (UUID targetId : targetIds) {
            encounter.getParticipants().stream()
                    .filter(p -> p.getId().equals(targetId))
                    .findFirst()
                    .ifPresent(targets::add);
        }
        return targets;
    }

    private TargetResult resolveSpellAttack(EncounterParticipant target, int spellAttackBonus,
                                             String damageDice, String damageType,
                                             List<String> conditions, Boolean advantage) {
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

        int total = roll + spellAttackBonus;
        boolean isNat20 = roll == 20;
        boolean isNat1 = roll == 1;

        if (isNat1) {
            return new TargetResult(target.getId(), target.getDisplayName(),
                    0, 0, false, List.of(), "miss", roll, total, null);
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
            String outcome = isNat20 ? "critical" : "hit";
            return new TargetResult(target.getId(), target.getDisplayName(),
                    damage, 0, false, conditions, outcome, roll, total, damageType);
        }

        return new TargetResult(target.getId(), target.getDisplayName(),
                0, 0, false, List.of(), "miss", roll, total, null);
    }

    private TargetResult resolveSavingThrow(EncounterParticipant target, int spellSaveDC,
                                             String saveAbility, String damageDice, String damageType,
                                             List<String> conditions, boolean halfOnSave) {
        int saveMod = getSaveModifier(target, saveAbility);
        int roll = ThreadLocalRandom.current().nextInt(1, 21);
        int total = roll + saveMod;
        boolean saved = total >= spellSaveDC;

        int damage = 0;
        if (damageDice != null) {
            damage = DiceRoller.roll(damageDice).total();
            if (saved && halfOnSave) {
                damage = Math.max(1, damage / 2);
            } else if (saved) {
                damage = 0;
            }
        }

        List<String> appliedConditions = saved ? List.of() : conditions;

        return new TargetResult(target.getId(), target.getDisplayName(),
                damage, 0, saved, appliedConditions, saved ? "saved" : "failed_save", roll, total, damageType);
    }

    private TargetResult resolveAutoHit(EncounterParticipant target, String damageDice,
                                         String damageType, List<String> conditions) {
        int damage = 0;
        if (damageDice != null) {
            damage = DiceRoller.roll(damageDice).total();
        }
        return new TargetResult(target.getId(), target.getDisplayName(),
                damage, 0, false, conditions, "hit", null, null, damageType);
    }

    private int resolveHealing(JsonNode healingNode, int spellLevel, int slotLevel,
                               EncounterParticipant caster, int spellAttackBonus) {
        if (healingNode == null) return 0;

        String healDice = healingNode.path("healingDice").asText(null);
        if (healDice == null) {
            healDice = healingNode.path("dice").asText(null);
        }
        if (healDice == null) return 0;

        healDice = substituteModPlaceholders(healDice, caster, spellAttackBonus);

        JsonNode upcastScaling = healingNode.get("upcastScaling");
        if (upcastScaling != null && slotLevel > spellLevel) {
            healDice = scaleUpcastDice(healDice, upcastScaling, slotLevel - spellLevel);
        }

        return DiceRoller.roll(healDice).total();
    }

    int getSaveModifier(EncounterParticipant target, String saveAbility) {
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
                String abilityFull = ABILITY_KEYS.getOrDefault(ability.toLowerCase().substring(0, 3), ability);
                for (JsonNode s : saves) {
                    String val = s.asText();
                    if (val.equalsIgnoreCase(abilityUpper)
                            || val.equalsIgnoreCase(abilityFull)
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

    private SpellCastResult manualResult(String spellName, String reason) {
        return new SpellCastResult(false,
                spellName + " cast — " + reason,
                0, 0, false, null, null,
                List.of(), true, reason, List.of());
    }

    private String formatAttackResult(TargetResult r) {
        if ("miss".equals(r.attackOutcome())) {
            return "Misses " + r.targetName() + " (" + r.rollValue() + "+" + (r.rollTotal() - r.rollValue()) + "=" + r.rollTotal() + " vs AC).";
        }
        String prefix = "critical".equals(r.attackOutcome()) ? "Critical hit on " : "Hits ";
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
        return r.damage() > 0
                ? r.targetName() + " takes " + r.damage() + " damage."
                : r.targetName() + " affected.";
    }
}
