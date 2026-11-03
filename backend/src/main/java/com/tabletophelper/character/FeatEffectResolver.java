package com.tabletophelper.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.Ability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
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
            FeatResourceEntry resource,
            List<Map<String, Object>> optionalFeaturesAdded,
            List<KnownSpell> spellsAdded
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
        FeatResourceEntry resource = null;
        List<Map<String, Object>> optionalFeaturesAdded = new ArrayList<>();
        List<KnownSpell> spellsAdded = new ArrayList<>();

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

    public void reverseFeatEffects(PlayerCharacter character, AppliedFeatEffects appliedEffects) {
        if (appliedEffects == null) return;

        Map<String, Integer> abilityIncreases = appliedEffects.abilityIncreases();
        if (abilityIncreases != null) {
            for (Map.Entry<String, Integer> entry : abilityIncreases.entrySet()) {
                int bonus = entry.getValue() != null ? entry.getValue() : 0;
                reverseAbilityIncrease(character, entry.getKey(), bonus);
            }
        }

        List<String> resistances = appliedEffects.resistancesAdded();
        if (resistances != null && !resistances.isEmpty()) {
            character.setDamageResistances(without(character.getDamageResistances(), resistances));
        }

        List<String> armor = appliedEffects.armorProficienciesAdded();
        if (armor != null && !armor.isEmpty()) {
            character.setArmorProficiencies(without(character.getArmorProficiencies(), armor));
        }
        List<String> weapons = appliedEffects.weaponProficienciesAdded();
        if (weapons != null && !weapons.isEmpty()) {
            character.setWeaponProficiencies(without(character.getWeaponProficiencies(), weapons));
        }
        List<String> tools = appliedEffects.toolProficienciesAdded();
        if (tools != null && !tools.isEmpty()) {
            character.setToolProficiencies(without(character.getToolProficiencies(), tools));
        }
        List<String> skills = appliedEffects.skillProficienciesAdded();
        if (skills != null && !skills.isEmpty()) {
            character.setSkillProficiencies(without(character.getSkillProficiencies(), skills));
        }
        List<String> languages = appliedEffects.languageProficienciesAdded();
        if (languages != null && !languages.isEmpty()) {
            character.setLanguageProficiencies(without(character.getLanguageProficiencies(), languages));
        }
        List<String> saves = appliedEffects.savingThrowProficienciesAdded();
        if (saves != null && !saves.isEmpty()) {
            character.setSavingThrowProficiencies(without(character.getSavingThrowProficiencies(), saves));
        }

        List<String> expertise = appliedEffects.expertiseAdded();
        if (expertise != null && !expertise.isEmpty()) {
            character.setSkillExpertises(without(character.getSkillExpertises(), expertise));
        }

        int speedBonus = appliedEffects.speedBonus() != null ? appliedEffects.speedBonus() : 0;
        if (speedBonus != 0) {
            character.setSpeed(character.getSpeed() - speedBonus);
        }

        int initBonus = appliedEffects.initiativeBonus() != null ? appliedEffects.initiativeBonus() : 0;
        if (initBonus != 0) {
            character.setInitiativeBonus(character.getInitiativeBonus() - initBonus);
        }

        int hpPerLevel = appliedEffects.hpPerLevel() != null ? appliedEffects.hpPerLevel() : 0;
        if (hpPerLevel != 0) {
            int hpLoss = hpPerLevel * character.getLevel();
            character.setHpMax(Math.max(1, character.getHpMax() - hpLoss));
            character.setHpCurrent(Math.min(character.getHpCurrent(), character.getHpMax()));
        }

        FeatResourceEntry resource = appliedEffects.resource();
        if (resource != null) {
            removeFeatResource(character, resource.featName());
        }

        List<KnownSpell> spellsAdded = appliedEffects.spellsAdded();
        if (spellsAdded != null && !spellsAdded.isEmpty()) {
            removeFeatSpells(character, spellsAdded);
        }

        String featName = appliedEffects.featName();
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
                int oldMod = Ability.modifier(character.getConstitution());
                character.setConstitution(Math.min(20, character.getConstitution() + amount));
                int newMod = Ability.modifier(character.getConstitution());
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
                                          ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getDamageResistances());

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

        character.setDamageResistances(current);
        return added;
    }

    // --- Proficiencies ---

    private List<String> applyListProficiency(PlayerCharacter character, JsonNode node,
                                               String fieldName,
                                               java.util.function.Function<PlayerCharacter, List<String>> getter,
                                               java.util.function.BiConsumer<PlayerCharacter, List<String>> setter) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(getter.apply(character));

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

        setter.accept(character, current);
        return added;
    }

    private List<String> getArmorProficiencies(PlayerCharacter c) { return c.getArmorProficiencies(); }
    private void setArmorProficiencies(PlayerCharacter c, List<String> v) { c.setArmorProficiencies(v); }

    private List<String> applyWeaponProficiencies(PlayerCharacter character, JsonNode node,
                                                   ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getWeaponProficiencies());

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

        character.setWeaponProficiencies(current);
        return added;
    }

    private List<String> applyToolProficiencies(PlayerCharacter character, JsonNode node,
                                                 ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getToolProficiencies());

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

        character.setToolProficiencies(current);
        return added;
    }

    private List<String> applySkillProficiencies(PlayerCharacter character, JsonNode node,
                                                   ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getSkillProficiencies());

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

        character.setSkillProficiencies(current);
        return added;
    }

    private List<String> applyLanguageProficiencies(PlayerCharacter character, JsonNode node,
                                                      ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getLanguageProficiencies());

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

        character.setLanguageProficiencies(current);
        return added;
    }

    private List<String> applySavingThrowProficiencies(PlayerCharacter character, JsonNode node,
                                                        ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getSavingThrowProficiencies());

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

        character.setSavingThrowProficiencies(current);
        return added;
    }

    private Map<String, List<String>> applySkillToolLanguageProficiencies(
            PlayerCharacter character, JsonNode node, ApplyChoicesRequest.AsiChoice choices) {
        Map<String, List<String>> result = new HashMap<>();
        result.put("skills", new ArrayList<>());
        result.put("tools", new ArrayList<>());

        if (choices == null) return result;

        if (choices.getSkillProficiencyChoices() != null) {
            List<String> current = copy(character.getSkillProficiencies());
            for (String skill : choices.getSkillProficiencyChoices()) {
                String normalized = titleCase(skill);
                if (!current.contains(normalized)) {
                    current.add(normalized);
                    result.get("skills").add(normalized);
                }
            }
            character.setSkillProficiencies(current);
        }

        if (choices.getToolProficiencyChoices() != null) {
            List<String> current = copy(character.getToolProficiencies());
            for (String tool : choices.getToolProficiencyChoices()) {
                if (!current.contains(tool)) {
                    current.add(tool);
                    result.get("tools").add(tool);
                }
            }
            character.setToolProficiencies(current);
        }

        return result;
    }

    // --- Expertise ---

    private List<String> applyExpertise(PlayerCharacter character, JsonNode node,
                                         ApplyChoicesRequest.AsiChoice choices) {
        List<String> added = new ArrayList<>();
        List<String> current = copy(character.getSkillExpertises());

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

        character.setSkillExpertises(current);
        return added;
    }

    // --- Resource (Lucky) ---

    private FeatResourceEntry applyResource(PlayerCharacter character, JsonNode resourceNode, String featName) {
        List<FeatResourceEntry> resources = copy(character.getFeatResources());

        FeatResourceEntry newResource = new FeatResourceEntry(featName,
                resourceNode.path("name").asText(featName),
                resourceNode.path("maxUses").asInt(),
                resourceNode.path("maxUses").asInt(),
                resourceNode.path("resetOn").asText("longRest"));
        resources.add(newResource);

        character.setFeatResources(resources);
        return newResource;
    }

    private void removeFeatResource(PlayerCharacter character, String featName) {
        List<FeatResourceEntry> resources = copy(character.getFeatResources());
        resources.removeIf(r -> featName.equals(r.featName()));
        character.setFeatResources(resources.isEmpty() ? null : resources);
    }

    // --- Spells ---

    private List<KnownSpell> applyFeatSpells(PlayerCharacter character, List<UUID> spellIds, Feat feat) {
        List<KnownSpell> spellsKnown = copy(character.getSpellsKnown());
        List<KnownSpell> added = new ArrayList<>();

        String source = "feat:" + feat.getName();
        Map<String, Integer> fixedSpellUsage = parseFeatSpellUsage(feat.getGrantsFeatures());

        for (UUID spellId : spellIds) {
            Spell spell = spellRepository.findById(spellId).orElse(null);
            Integer usesPerDay = spell != null ? fixedSpellUsage.get(spell.getName().toLowerCase()) : null;
            int level = spell != null && spell.getLevel() != null ? spell.getLevel() : 0;
            KnownSpell entry = new KnownSpell(null, spell != null ? spell.getName() : null, level, source, null, null,
                    spell != null && level == 0 ? Boolean.TRUE : null,
                    usesPerDay != null && usesPerDay > 0 ? usesPerDay : null, null);
            spellsKnown.add(entry);
            added.add(entry);
        }

        character.setSpellsKnown(spellsKnown);
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
        } catch (Exception e) {
            log.warn("Failed to parse feat spell usage from grantsFeatures JSON", e);
        }
        return usage;
    }

    private void removeFeatSpells(PlayerCharacter character, List<KnownSpell> spellsToRemove) {
        if (character.getSpellsKnown() == null) return;
        List<KnownSpell> spellsKnown = copy(character.getSpellsKnown());

        for (KnownSpell toRemove : spellsToRemove) {
            UUID id = toRemove.id();
            String name = toRemove.name();
            String source = toRemove.source();
            spellsKnown.removeIf(s -> {
                if (id != null && id.equals(s.id())) return true;
                return name != null && name.equals(s.name())
                        && source != null && source.equals(s.source());
            });
        }
        character.setSpellsKnown(spellsKnown);
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

    private void addFeatFeature(PlayerCharacter character, Feat feat) {
        List<Feature> features = copy(character.getFeatures());
        features.add(new Feature(feat.getName(),
                feat.getDescription() != null ? feat.getDescription() : "Feat", "Feat"));
        character.setFeatures(features);
    }

    private void removeFeatFeature(PlayerCharacter character, String featName) {
        List<Feature> features = copy(character.getFeatures());
        features.removeIf(f -> featName.equals(f.name()) && "Feat".equals(f.source()));
        character.setFeatures(features);
    }

    // --- Helpers ---

    private static <T> List<T> copy(List<T> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    // The list without the items levelling down takes away
    private static List<String> without(List<String> current, List<String> toRemove) {
        List<String> list = copy(current);
        list.removeAll(toRemove);
        return list;
    }

    private String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
