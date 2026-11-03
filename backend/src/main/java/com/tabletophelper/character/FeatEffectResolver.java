package com.tabletophelper.character;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatEffectResolver {

    /** The source of a feat's entry among the character's features, whatever granted the feat. */
    static final String FEAT_SOURCE = "Feat";

    // "You can select this feat multiple times", as Elemental Adept puts it
    private static final Pattern REPEATABLE = Pattern.compile("(?i)\\b(?:select|take) this feat multiple times\\b");

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
            List<KnownSpell> spellsAdded,
            String spellcastingAbility
    ) {}

    /**
     * Applies a feat's effects and records what they changed.
     *
     * @throws ConflictException when the feat's data can't be read (D155)
     */
    public AppliedEffects applyFeat(PlayerCharacter character, Feat feat, ApplyChoicesRequest.AsiChoice choices) {
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
            abilityIncreases = applyAbilityScoreIncrease(character, feat, choices);
        }

        // Apply structured effects
        if (feat.getEffects() != null) {
            JsonNode effects = readFeatData(feat.getName(), feat.getEffects());

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
            // Speed, initiative and hit points are Derived Stats: the bonuses are recorded, and CharacterStats adds
            // them (D167)
            if (effects.has("speedBonus")) {
                speedBonus = effects.get("speedBonus").asInt();
            }
            if (effects.has("initiativeBonus")) {
                initiativeBonus = effects.get("initiativeBonus").asInt();
            }
            if (effects.has("hpPerLevel")) {
                hpPerLevel = effects.get("hpPerLevel").asInt();
            }
            if (effects.has("passivePerceptionBonus")) {
                passivePerceptionBonus = effects.get("passivePerceptionBonus").asInt();
            }
            if (effects.has("passiveInvestigationBonus")) {
                passiveInvestigationBonus = effects.get("passiveInvestigationBonus").asInt();
            }
            if (effects.has("resource")) {
                resource = resource(effects.get("resource"), feat.getName());
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
        String spellcastingAbility = !spellsAdded.isEmpty() ? setSpellcastingAbility(character, choices) : null;

        // Add feat as a feature with full description
        addFeatFeature(character, feat);

        return new AppliedEffects(
                abilityIncreases, resistancesAdded, armorProfsAdded, weaponProfsAdded,
                toolProfsAdded, skillProfsAdded, langProfsAdded, saveProfsAdded,
                expertiseAdded, speedBonus, initiativeBonus, hpPerLevel,
                passivePerceptionBonus, passiveInvestigationBonus, resource,
                optionalFeaturesAdded, spellsAdded, spellcastingAbility);
    }

    /**
     * Takes away what applying a feat recorded, as levelling down past the level that took it, or removing the DM's
     * grant of it, does. Speed, initiative, hit points and the pool of the feat's limited uses need nothing here:
     * CharacterStats works them out again from the feats the character still has.
     */
    public void reverseFeatEffects(PlayerCharacter character, AppliedFeatEffects appliedEffects) {
        if (appliedEffects == null) return;

        Map<String, Integer> abilityIncreases = appliedEffects.abilityIncreases();
        if (abilityIncreases != null) {
            for (Map.Entry<String, Integer> entry : abilityIncreases.entrySet()) {
                int bonus = entry.getValue() != null ? entry.getValue() : 0;
                CharacterUpdates.decreaseAbility(character, entry.getKey(), bonus);
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

        List<KnownSpell> spellsAdded = appliedEffects.spellsAdded();
        if (spellsAdded != null && !spellsAdded.isEmpty()) {
            removeFeatSpells(character, spellsAdded);
        }
        if (appliedEffects.spellcastingAbility() != null) {
            character.setSpellcastingAbility(null);
        }

        String featName = appliedEffects.featName();
        if (featName != null) {
            removeFeatFeature(character, featName);
        }
    }

    private JsonNode readFeatData(String featName, String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ConflictException("The effects of the feat " + featName + " can't be read");
        }
    }

    // --- Ability Score ---

    // Each increase the feat gives, by the amount actually applied under the maximum (D167)
    private Map<String, Integer> applyAbilityScoreIncrease(PlayerCharacter character, Feat feat,
                                                            ApplyChoicesRequest.AsiChoice choices) {
        Map<String, Integer> applied = new LinkedHashMap<>();
        JsonNode asiArray = readFeatData(feat.getName(), feat.getAbilityScoreIncrease());
        if (!asiArray.isArray()) return applied;

        for (JsonNode entry : asiArray) {
            if (entry.has("choose") && choices != null && choices.getFeatAbility() != null) {
                String ability = choices.getFeatAbility().toLowerCase();
                int amount = entry.path("choose").path("amount").asInt(1);
                applied.put(ability, CharacterUpdates.increaseAbility(character, ability, amount));
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!"choose".equals(field.getKey())) {
                        String ability = field.getKey().toLowerCase();
                        int amount = field.getValue().asInt();
                        applied.put(ability, CharacterUpdates.increaseAbility(character, ability, amount));
                    }
                }
            }
        }
        return applied;
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

    // The feat's limited uses, recorded with its effects; CharacterStats gives the sheet a feat:<feat> pool of them,
    // which levelling down removes with the level (D158)
    private static FeatResourceEntry resource(JsonNode resourceNode, String featName) {
        int uses = resourceNode.path("maxUses").asInt();
        return new FeatResourceEntry(featName, resourceNode.path("name").asText(featName), uses, uses,
                resourceNode.path("resetOn").asText("longRest"));
    }

    // --- Spells ---

    /**
     * Makes the feat's chosen ability, such as Fey Touched's, the race and feat spellcasting ability its spells are
     * cast with, when the character has none yet (D159), and returns it so the history can record it; null when the
     * character already has one or the feat's choice names no ability. A character has one such ability for its race
     * and feat spells together.
     */
    private static String setSpellcastingAbility(PlayerCharacter character, ApplyChoicesRequest.AsiChoice choices) {
        String current = character.getSpellcastingAbility();
        if (current != null && !current.isBlank()) {
            return null;
        }
        Optional<Ability> chosen = Ability.parse(choices.getFeatAbility());
        chosen.ifPresent(ability -> character.setSpellcastingAbility(ability.name()));
        return chosen.map(Ability::name).orElse(null);
    }

    private List<KnownSpell> applyFeatSpells(PlayerCharacter character, List<UUID> spellIds, Feat feat) {
        List<KnownSpell> spellsKnown = copy(character.getSpellsKnown());
        List<KnownSpell> added = new ArrayList<>();

        String source = "feat:" + feat.getName();
        Map<String, Integer> fixedSpellUsage = parseFeatSpellUsage(feat.getName(), feat.getGrantsFeatures());

        for (UUID spellId : spellIds) {
            Spell spell = spellRepository.findById(spellId)
                    .orElseThrow(() -> new ValidationException("Spell not found: " + spellId));
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

    // The uses per long rest of each spell a feat grants by name; feat data that can't be read is a 409 (D155)
    private Map<String, Integer> parseFeatSpellUsage(String featName, String grantsFeatures) {
        Map<String, Integer> usage = new HashMap<>();
        if (grantsFeatures == null) return usage;
        try {
            JsonNode arr = objectMapper.readTree(grantsFeatures);
            if (!arr.isArray()) return usage;
            for (JsonNode option : arr) {
                JsonNode daily = option.path("innate").path("_").path("daily");
                if (daily.isObject()) {
                    daily.fields().forEachRemaining(e -> {
                        // Keys such as "1" or "1e" (once each): the uses before any "e"
                        String count = e.getKey().replace("e", "");
                        if (!count.matches("\\d+")) {
                            throw new ConflictException("The spells the feat " + featName + " grants can't be read");
                        }
                        int uses = Integer.parseInt(count);
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
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse feat spell usage from grantsFeatures JSON", e);
            throw new ConflictException("The spells the feat " + featName + " grants can't be read");
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
                feat.getDescription() != null ? feat.getDescription() : "Feat", FEAT_SOURCE));
        character.setFeatures(features);
    }

    // The latest of the feat's entries goes, so a feat taken more than once keeps the others' (D158)
    private void removeFeatFeature(PlayerCharacter character, String featName) {
        List<Feature> features = copy(character.getFeatures());
        for (int i = features.size() - 1; i >= 0; i--) {
            Feature feature = features.get(i);
            if (featName.equals(feature.name()) && FEAT_SOURCE.equals(feature.source())) {
                features.remove(i);
                break;
            }
        }
        character.setFeatures(features);
    }

    /**
     * Whether a character may take the feat more than once: its text says it can be selected or taken multiple times,
     * as Elemental Adept's does (PHB p.166). The feat data records nothing else that says so.
     */
    public static boolean repeatable(Feat feat) {
        return feat.getDescription() != null && REPEATABLE.matcher(feat.getDescription()).find();
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
