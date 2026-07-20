package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class FeatEffectResolver {

    private final ObjectMapper objectMapper;
    private final SpellRepository spellRepository;

    public record AppliedEffects(
            Map<String, Integer> abilityIncreases,
            List<String> resistancesAdded,
            List<String> armorProficienciesAdded,
            List<String> weaponProficienciesAdded,
            List<String> toolProficienciesAdded,
            List<String> skillProficienciesAdded,
            List<String> languageProficienciesAdded,
            List<String> savingThrowProficienciesAdded,
            List<String> expertiseAdded,
            int speedBonus,
            int initiativeBonus,
            int hpPerLevel,
            int passivePerceptionBonus,
            int passiveInvestigationBonus,
            Map<String, Object> resource,
            List<Map<String, Object>> optionalFeaturesAdded,
            List<Map<String, Object>> spellsAdded
    ) {}

    public AppliedEffects applyFeat(PlayerCharacter character, Feat feat,
                                     ApplyChoicesRequest.AsiChoice choices) throws Exception {
        Map<String, Integer> abilityIncreases = new LinkedHashMap<>();
        List<String> resistancesAdded = new ArrayList<>();
        List<String> armorProfsAdded = new ArrayList<>();
        List<String> weaponProfsAdded = new ArrayList<>();
        List<String> toolProfsAdded = new ArrayList<>();
        List<String> skillProfsAdded = new ArrayList<>();
        List<String> langProfsAdded = new ArrayList<>();
        List<String> saveProfsAdded = new ArrayList<>();
        List<String> expertiseAdded = new ArrayList<>();
        int speedBonus = 0;
        int initiativeBonus = 0;
        int hpPerLevel = 0;
        int passivePerceptionBonus = 0;
        int passiveInvestigationBonus = 0;
        Map<String, Object> resource = null;
        List<Map<String, Object>> optionalFeaturesAdded = new ArrayList<>();
        List<Map<String, Object>> spellsAdded = new ArrayList<>();

        // Apply ability score increase from feat
        if (feat.getAbilityScoreIncrease() != null) {
            abilityIncreases = applyAbilityScoreIncrease(character, feat.getAbilityScoreIncrease(), choices);
        }

        // Apply structured effects
        if (feat.getEffects() != null) {
            JsonNode effects = objectMapper.readTree(feat.getEffects());

            if (effects.has("resistances")) {
                resistancesAdded = applyResistances(character, effects.get("resistances"), choices);
            }
            if (effects.has("armorProficiencies")) {
                armorProfsAdded = applyListProficiency(character, effects.get("armorProficiencies"),
                        "armorProficiencies", this::getArmorProficiencies, this::setArmorProficiencies);
            }
            if (effects.has("weaponProficiencies")) {
                weaponProfsAdded = applyWeaponProficiencies(character, effects.get("weaponProficiencies"), choices);
            }
            if (effects.has("toolProficiencies")) {
                toolProfsAdded = applyToolProficiencies(character, effects.get("toolProficiencies"), choices);
            }
            if (effects.has("skillProficiencies")) {
                skillProfsAdded = applySkillProficiencies(character, effects.get("skillProficiencies"), choices);
            }
            if (effects.has("languageProficiencies")) {
                langProfsAdded = applyLanguageProficiencies(character, effects.get("languageProficiencies"), choices);
            }
            if (effects.has("savingThrowProficiencies")) {
                saveProfsAdded = applySavingThrowProficiencies(character, effects.get("savingThrowProficiencies"), choices);
            }
            if (effects.has("skillToolLanguageProficiencies")) {
                Map<String, List<String>> mixed = applySkillToolLanguageProficiencies(
                        character, effects.get("skillToolLanguageProficiencies"), choices);
                skillProfsAdded.addAll(mixed.getOrDefault("skills", List.of()));
                toolProfsAdded.addAll(mixed.getOrDefault("tools", List.of()));
            }
            if (effects.has("expertise")) {
                expertiseAdded = applyExpertise(character, effects.get("expertise"), choices);
            }
            if (effects.has("speedBonus")) {
                speedBonus = effects.get("speedBonus").asInt();
                character.setSpeed(character.getSpeed() + speedBonus);
            }
            if (effects.has("initiativeBonus")) {
                initiativeBonus = effects.get("initiativeBonus").asInt();
                character.setInitiativeBonus(character.getInitiativeBonus() + initiativeBonus);
            }
            if (effects.has("hpPerLevel")) {
                hpPerLevel = effects.get("hpPerLevel").asInt();
                int hpGain = hpPerLevel * character.getLevel();
                character.setHpMax(character.getHpMax() + hpGain);
                character.setHpCurrent(character.getHpCurrent() + hpGain);
            }
            if (effects.has("passivePerceptionBonus")) {
                passivePerceptionBonus = effects.get("passivePerceptionBonus").asInt();
            }
            if (effects.has("passiveInvestigationBonus")) {
                passiveInvestigationBonus = effects.get("passiveInvestigationBonus").asInt();
            }
            if (effects.has("resource")) {
                resource = applyResource(character, effects.get("resource"), feat.getName());
            }
            if (effects.has("optionalFeatureProgression") && choices != null
                    && choices.getOptionalFeatureIds() != null) {
                optionalFeaturesAdded = recordOptionalFeatures(choices.getOptionalFeatureIds());
            }
        }

        // Apply spell choices
        if (choices != null && choices.getSpellIds() != null && !choices.getSpellIds().isEmpty()) {
            spellsAdded = applyFeatSpells(character, choices.getSpellIds(), feat);
        }

        // Add feat as a feature with full description
        addFeatFeature(character, feat);

        return new AppliedEffects(
                abilityIncreases, resistancesAdded, armorProfsAdded, weaponProfsAdded,
                toolProfsAdded, skillProfsAdded, langProfsAdded, saveProfsAdded,
                expertiseAdded, speedBonus, initiativeBonus, hpPerLevel,
                passivePerceptionBonus, passiveInvestigationBonus, resource,
                optionalFeaturesAdded, spellsAdded);
    }

    public void reverseFeatEffects(PlayerCharacter character, Map<String, Object> appliedEffects) throws Exception {
        if (appliedEffects == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> abilityIncreases = (Map<String, Object>) appliedEffects.get("abilityIncreases");
        if (abilityIncreases != null) {
            for (Map.Entry<String, Object> entry : abilityIncreases.entrySet()) {
                int bonus = entry.getValue() instanceof Number n ? n.intValue() : 0;
                reverseAbilityIncrease(character, entry.getKey(), bonus);
            }
        }

        @SuppressWarnings("unchecked")
        List<String> resistances = (List<String>) appliedEffects.get("resistancesAdded");
        if (resistances != null && !resistances.isEmpty()) {
            removeFromJsonList(character, "damageResistances", resistances);
        }

        reverseListProficiency(character, appliedEffects, "armorProficienciesAdded", "armorProficiencies");
        reverseListProficiency(character, appliedEffects, "weaponProficienciesAdded", "weaponProficiencies");
        reverseListProficiency(character, appliedEffects, "toolProficienciesAdded", "toolProficiencies");
        reverseListProficiency(character, appliedEffects, "skillProficienciesAdded", "skillProficiencies");
        reverseListProficiency(character, appliedEffects, "languageProficienciesAdded", "languageProficiencies");
        reverseListProficiency(character, appliedEffects, "savingThrowProficienciesAdded", "savingThrowProficiencies");

        @SuppressWarnings("unchecked")
        List<String> expertise = (List<String>) appliedEffects.get("expertiseAdded");
        if (expertise != null && !expertise.isEmpty()) {
            removeFromJsonList(character, "skillExpertises", expertise);
        }

        int speedBonus = appliedEffects.get("speedBonus") instanceof Number n ? n.intValue() : 0;
        if (speedBonus != 0) {
            character.setSpeed(character.getSpeed() - speedBonus);
        }

        int initBonus = appliedEffects.get("initiativeBonus") instanceof Number n ? n.intValue() : 0;
        if (initBonus != 0) {
            character.setInitiativeBonus(character.getInitiativeBonus() - initBonus);
        }

        int hpPerLevel = appliedEffects.get("hpPerLevel") instanceof Number n ? n.intValue() : 0;
        if (hpPerLevel != 0) {
            int hpLoss = hpPerLevel * character.getLevel();
            character.setHpMax(Math.max(1, character.getHpMax() - hpLoss));
            character.setHpCurrent(Math.min(character.getHpCurrent(), character.getHpMax()));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) appliedEffects.get("resource");
        if (resource != null) {
            String featName = (String) resource.get("featName");
            removeFeatResource(character, featName);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spellsAdded = (List<Map<String, Object>>) appliedEffects.get("spellsAdded");
        if (spellsAdded != null && !spellsAdded.isEmpty()) {
            removeFeatSpells(character, spellsAdded);
        }

        String featName = (String) appliedEffects.get("featName");
        if (featName != null) {
            removeFeatFeature(character, featName);
        }
    }

    // --- Ability Score ---

    private Map<String, Integer> applyAbilityScoreIncrease(PlayerCharacter character,
                                                            String asiJson, ApplyChoicesRequest.AsiChoice choices) throws Exception {
        Map<String, Integer> applied = new LinkedHashMap<>();
        JsonNode asiArray = objectMapper.readTree(asiJson);
        if (!asiArray.isArray()) return applied;

        for (JsonNode entry : asiArray) {
            if (entry.has("choose") && choices != null && choices.getFeatAbility() != null) {
                String ability = choices.getFeatAbility().toLowerCase();
                int amount = entry.path("choose").path("amount").asInt(1);
                applyAbilityChange(character, ability, amount);
                applied.put(ability, amount);
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!"choose".equals(field.getKey())) {
                        String ability = field.getKey().toLowerCase();
                        int amount = field.getValue().asInt();
                        applyAbilityChange(character, ability, amount);
                        applied.put(ability, amount);
                    }
                }
            }
        }
        return applied;
    }

    private void applyAbilityChange(PlayerCharacter character, String ability, int amount) {
        switch (ability.toLowerCase()) {
            case "str", "strength" -> character.setStrength(Math.min(20, character.getStrength() + amount));
            case "dex", "dexterity" -> character.setDexterity(Math.min(20, character.getDexterity() + amount));
            case "con", "constitution" -> {
                int oldMod = abilityMod(character.getConstitution());
                character.setConstitution(Math.min(20, character.getConstitution() + amount));
                int newMod = abilityMod(character.getConstitution());
                int hpAdjust = (newMod - oldMod) * character.getLevel();
                character.setHpMax(character.getHpMax() + hpAdjust);
                character.setHpCurrent(character.getHpCurrent() + hpAdjust);
            }
            case "int", "intelligence" -> character.setIntelligence(Math.min(20, character.getIntelligence() + amount));
            case "wis", "wisdom" -> character.setWisdom(Math.min(20, character.getWisdom() + amount));
            case "cha", "charisma" -> character.setCharisma(Math.min(20, character.getCharisma() + amount));
        }
    }

    private void reverseAbilityIncrease(PlayerCharacter character, String ability, int amount) {
        applyAbilityChange(character, ability, -amount);
    }

    // --- Resistances ---

    private List<String> applyResistances(PlayerCharacter character, JsonNode resistNode,
                                          ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getDamageResistances());

        for (JsonNode item : resistNode) {
            if (item.isTextual()) {
                String resist = item.asText();
                if (!current.contains(resist)) {
                    current.add(resist);
                    added.add(resist);
                }
            } else if (item.isObject() && item.has("choose") && choices != null
                    && choices.getResistanceChoice() != null) {
                String resist = choices.getResistanceChoice();
                if (!current.contains(resist)) {
                    current.add(resist);
                    added.add(resist);
                }
            }
        }

        character.setDamageResistances(objectMapper.writeValueAsString(current));
        return added;
    }

    // --- Proficiencies ---

    private List<String> applyListProficiency(PlayerCharacter character, JsonNode node,
                                               String fieldName,
                                               java.util.function.Function<PlayerCharacter, String> getter,
                                               java.util.function.BiConsumer<PlayerCharacter, String> setter) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(getter.apply(character));

        for (JsonNode item : node) {
            if (item.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue().asBoolean(false)) {
                        String prof = titleCase(field.getKey());
                        if (!current.contains(prof)) {
                            current.add(prof);
                            added.add(prof);
                        }
                    }
                }
            }
        }

        setter.accept(character, objectMapper.writeValueAsString(current));
        return added;
    }

    private String getArmorProficiencies(PlayerCharacter c) { return c.getArmorProficiencies(); }
    private void setArmorProficiencies(PlayerCharacter c, String v) { c.setArmorProficiencies(v); }

    private List<String> applyWeaponProficiencies(PlayerCharacter character, JsonNode node,
                                                   ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getWeaponProficiencies());

        for (JsonNode item : node) {
            if (item.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if ("choose".equals(field.getKey())) {
                        if (choices != null && choices.getWeaponChoices() != null) {
                            for (String weapon : choices.getWeaponChoices()) {
                                if (!current.contains(weapon)) {
                                    current.add(weapon);
                                    added.add(weapon);
                                }
                            }
                        }
                    } else if (field.getValue().asBoolean(false)) {
                        String prof = titleCase(field.getKey());
                        if (!current.contains(prof)) {
                            current.add(prof);
                            added.add(prof);
                        }
                    }
                }
            }
        }

        character.setWeaponProficiencies(objectMapper.writeValueAsString(current));
        return added;
    }

    private List<String> applyToolProficiencies(PlayerCharacter character, JsonNode node,
                                                 ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getToolProficiencies());

        for (JsonNode item : node) {
            if (item.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey();
                    if ("any".equals(key) || "anyArtisansTool".equals(key)) {
                        if (choices != null && choices.getToolProficiencyChoices() != null) {
                            for (String tool : choices.getToolProficiencyChoices()) {
                                if (!current.contains(tool)) {
                                    current.add(tool);
                                    added.add(tool);
                                }
                            }
                        }
                    } else if (field.getValue().asBoolean(false)) {
                        String prof = titleCase(key);
                        if (!current.contains(prof)) {
                            current.add(prof);
                            added.add(prof);
                        }
                    }
                }
            }
        }

        character.setToolProficiencies(objectMapper.writeValueAsString(current));
        return added;
    }

    private List<String> applySkillProficiencies(PlayerCharacter character, JsonNode node,
                                                   ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getSkillProficiencies());

        for (JsonNode item : node) {
            if (item.isObject() && item.has("choose")) {
                if (choices != null && choices.getSkillProficiencyChoices() != null) {
                    for (String skill : choices.getSkillProficiencyChoices()) {
                        String normalized = titleCase(skill);
                        if (!current.contains(normalized)) {
                            current.add(normalized);
                            added.add(normalized);
                        }
                    }
                }
            }
        }

        character.setSkillProficiencies(objectMapper.writeValueAsString(current));
        return added;
    }

    private List<String> applyLanguageProficiencies(PlayerCharacter character, JsonNode node,
                                                      ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getLanguageProficiencies());

        for (JsonNode item : node) {
            if (item.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if ("any".equals(field.getKey())) {
                        if (choices != null && choices.getLanguageChoices() != null) {
                            for (String lang : choices.getLanguageChoices()) {
                                if (!current.contains(lang)) {
                                    current.add(lang);
                                    added.add(lang);
                                }
                            }
                        }
                    } else if (field.getValue().asBoolean(false)) {
                        String lang = titleCase(field.getKey());
                        if (!current.contains(lang)) {
                            current.add(lang);
                            added.add(lang);
                        }
                    }
                }
            }
        }

        character.setLanguageProficiencies(objectMapper.writeValueAsString(current));
        return added;
    }

    private List<String> applySavingThrowProficiencies(PlayerCharacter character, JsonNode node,
                                                        ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getSavingThrowProficiencies());

        for (JsonNode item : node) {
            if (item.isObject() && item.has("choose")) {
                if (choices != null && choices.getSavingThrowChoice() != null) {
                    String save = choices.getSavingThrowChoice().toUpperCase();
                    if (!current.contains(save)) {
                        current.add(save);
                        added.add(save);
                    }
                }
            }
        }

        character.setSavingThrowProficiencies(objectMapper.writeValueAsString(current));
        return added;
    }

    private Map<String, List<String>> applySkillToolLanguageProficiencies(
            PlayerCharacter character, JsonNode node, ApplyChoicesRequest.AsiChoice choices) throws Exception {
        Map<String, List<String>> result = new HashMap<>();
        result.put("skills", new ArrayList<>());
        result.put("tools", new ArrayList<>());

        if (choices == null) return result;

        if (choices.getSkillProficiencyChoices() != null) {
            List<String> current = parseStringList(character.getSkillProficiencies());
            for (String skill : choices.getSkillProficiencyChoices()) {
                String normalized = titleCase(skill);
                if (!current.contains(normalized)) {
                    current.add(normalized);
                    result.get("skills").add(normalized);
                }
            }
            character.setSkillProficiencies(objectMapper.writeValueAsString(current));
        }

        if (choices.getToolProficiencyChoices() != null) {
            List<String> current = parseStringList(character.getToolProficiencies());
            for (String tool : choices.getToolProficiencyChoices()) {
                if (!current.contains(tool)) {
                    current.add(tool);
                    result.get("tools").add(tool);
                }
            }
            character.setToolProficiencies(objectMapper.writeValueAsString(current));
        }

        return result;
    }

    // --- Expertise ---

    private List<String> applyExpertise(PlayerCharacter character, JsonNode node,
                                         ApplyChoicesRequest.AsiChoice choices) throws Exception {
        List<String> added = new ArrayList<>();
        List<String> current = parseStringList(character.getSkillExpertises());

        for (JsonNode item : node) {
            if (item.isObject() && item.has("anyProficientSkill")) {
                if (choices != null && choices.getExpertiseSkillChoices() != null) {
                    for (String skill : choices.getExpertiseSkillChoices()) {
                        String normalized = titleCase(skill);
                        if (!current.contains(normalized)) {
                            current.add(normalized);
                            added.add(normalized);
                        }
                    }
                }
            }
        }

        character.setSkillExpertises(objectMapper.writeValueAsString(current));
        return added;
    }

    // --- Resource (Lucky) ---

    private Map<String, Object> applyResource(PlayerCharacter character, JsonNode resourceNode,
                                               String featName) throws Exception {
        List<Map<String, Object>> resources = parseFeatResources(character.getFeatResources());

        Map<String, Object> newResource = new LinkedHashMap<>();
        newResource.put("featName", featName);
        newResource.put("name", resourceNode.path("name").asText(featName));
        newResource.put("maxUses", resourceNode.path("maxUses").asInt());
        newResource.put("currentUses", resourceNode.path("maxUses").asInt());
        newResource.put("resetOn", resourceNode.path("resetOn").asText("longRest"));
        resources.add(newResource);

        character.setFeatResources(objectMapper.writeValueAsString(resources));
        return newResource;
    }

    private void removeFeatResource(PlayerCharacter character, String featName) throws Exception {
        List<Map<String, Object>> resources = parseFeatResources(character.getFeatResources());
        resources.removeIf(r -> featName.equals(r.get("featName")));
        character.setFeatResources(resources.isEmpty() ? null : objectMapper.writeValueAsString(resources));
    }

    // --- Spells ---

    private List<Map<String, Object>> applyFeatSpells(PlayerCharacter character, List<UUID> spellIds,
                                                       Feat feat) throws Exception {
        List<Map<String, Object>> spellsKnown = character.getSpellsKnown() != null
                ? objectMapper.readValue(character.getSpellsKnown(), new TypeReference<>() {})
                : new ArrayList<>();
        List<Map<String, Object>> added = new ArrayList<>();

        String source = "feat:" + feat.getName();
        Map<String, Integer> fixedSpellUsage = parseFeatSpellUsage(feat.getGrantsFeatures());

        for (UUID spellId : spellIds) {
            Spell spell = spellRepository.findById(spellId).orElse(null);
            Map<String, Object> entry = new LinkedHashMap<>();
            if (spell != null) {
                entry.put("name", spell.getName());
                entry.put("level", spell.getLevel());
            }
            entry.put("source", source);
            Integer usesPerDay = spell != null ? fixedSpellUsage.get(spell.getName().toLowerCase()) : null;
            if (usesPerDay != null && usesPerDay > 0) {
                entry.put("usesPerLongRest", usesPerDay);
            }
            if (spell != null && spell.getLevel() == 0) {
                entry.put("atWill", true);
            }
            spellsKnown.add(entry);
            added.add(new LinkedHashMap<>(entry));
        }

        character.setSpellsKnown(objectMapper.writeValueAsString(spellsKnown));
        return added;
    }

    private Map<String, Integer> parseFeatSpellUsage(String grantsFeatures) {
        Map<String, Integer> usage = new HashMap<>();
        if (grantsFeatures == null) return usage;
        try {
            JsonNode arr = objectMapper.readTree(grantsFeatures);
            if (!arr.isArray()) return usage;
            for (JsonNode option : arr) {
                JsonNode daily = option.path("innate").path("_").path("daily");
                if (daily.isObject()) {
                    daily.fields().forEachRemaining(e -> {
                        int uses = 1;
                        try { uses = Integer.parseInt(e.getKey().replace("e", "")); } catch (NumberFormatException ignored) {}
                        for (JsonNode spell : e.getValue()) {
                            if (spell.isTextual()) {
                                usage.put(spell.asText().toLowerCase(), uses);
                            }
                        }
                    });
                }
                JsonNode known = option.path("known").path("_");
                if (known.isArray()) {
                    for (JsonNode item : known) {
                        if (item.isTextual()) {
                            usage.put(item.asText().replace("#c", "").replaceAll("#\\d+$", "")
                                    .replaceAll("\\|.*$", "").toLowerCase(), 0);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return usage;
    }

    private void removeFeatSpells(PlayerCharacter character, List<Map<String, Object>> spellsToRemove) throws Exception {
        if (character.getSpellsKnown() == null) return;
        List<Map<String, Object>> spellsKnown = objectMapper.readValue(
                character.getSpellsKnown(), new TypeReference<>() {});

        for (Map<String, Object> toRemove : spellsToRemove) {
            String id = (String) toRemove.get("id");
            String name = (String) toRemove.get("name");
            String source = (String) toRemove.get("source");
            spellsKnown.removeIf(s -> {
                if (id != null && id.equals(s.get("id"))) return true;
                return name != null && name.equals(s.get("name"))
                        && source != null && source.equals(s.get("source"));
            });
        }
        character.setSpellsKnown(objectMapper.writeValueAsString(spellsKnown));
    }

    // --- Optional Features ---

    private List<Map<String, Object>> recordOptionalFeatures(List<UUID> featureIds) {
        List<Map<String, Object>> recorded = new ArrayList<>();
        for (UUID id : featureIds) {
            recorded.add(Map.of("id", id.toString()));
        }
        return recorded;
    }

    // --- Feature entry ---

    private void addFeatFeature(PlayerCharacter character, Feat feat) throws Exception {
        List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
        Map<String, Object> featEntry = new LinkedHashMap<>();
        featEntry.put("name", feat.getName());
        featEntry.put("description", feat.getDescription() != null ? feat.getDescription() : "Feat");
        featEntry.put("source", "Feat");
        features.add(featEntry);
        character.setFeatures(objectMapper.writeValueAsString(features));
    }

    private void removeFeatFeature(PlayerCharacter character, String featName) throws Exception {
        List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
        features.removeIf(f -> featName.equals(f.get("name")) && "Feat".equals(f.get("source")));
        character.setFeatures(objectMapper.writeValueAsString(features));
    }

    // --- Helpers ---

    private List<String> parseStringList(String json) throws Exception {
        if (json == null || json.isBlank()) return new ArrayList<>();
        return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFeaturesList(String json) throws Exception {
        if (json == null || json.isBlank()) return new ArrayList<>();
        return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFeatResources(String json) throws Exception {
        if (json == null || json.isBlank()) return new ArrayList<>();
        return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}));
    }

    private void removeFromJsonList(PlayerCharacter character, String fieldName, List<String> toRemove) throws Exception {
        String json = switch (fieldName) {
            case "damageResistances" -> character.getDamageResistances();
            case "skillExpertises" -> character.getSkillExpertises();
            default -> null;
        };

        List<String> current = parseStringList(json);
        current.removeAll(toRemove);
        String updated = objectMapper.writeValueAsString(current);

        switch (fieldName) {
            case "damageResistances" -> character.setDamageResistances(updated);
            case "skillExpertises" -> character.setSkillExpertises(updated);
        }
    }

    @SuppressWarnings("unchecked")
    private void reverseListProficiency(PlayerCharacter character, Map<String, Object> appliedEffects,
                                        String effectsKey, String characterField) throws Exception {
        List<String> added = (List<String>) appliedEffects.get(effectsKey);
        if (added == null || added.isEmpty()) return;

        String json = switch (characterField) {
            case "armorProficiencies" -> character.getArmorProficiencies();
            case "weaponProficiencies" -> character.getWeaponProficiencies();
            case "toolProficiencies" -> character.getToolProficiencies();
            case "skillProficiencies" -> character.getSkillProficiencies();
            case "languageProficiencies" -> character.getLanguageProficiencies();
            case "savingThrowProficiencies" -> character.getSavingThrowProficiencies();
            default -> null;
        };

        List<String> current = parseStringList(json);
        current.removeAll(added);
        String updated = objectMapper.writeValueAsString(current);

        switch (characterField) {
            case "armorProficiencies" -> character.setArmorProficiencies(updated);
            case "weaponProficiencies" -> character.setWeaponProficiencies(updated);
            case "toolProficiencies" -> character.setToolProficiencies(updated);
            case "skillProficiencies" -> character.setSkillProficiencies(updated);
            case "languageProficiencies" -> character.setLanguageProficiencies(updated);
            case "savingThrowProficiencies" -> character.setSavingThrowProficiencies(updated);
        }
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static int abilityMod(int score) {
        return (score - 10) / 2;
    }
}
