package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.reference.*;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignMemberRepository campaignMemberRepository;
    private final RaceRepository raceRepository;
    private final CharacterClassRepository characterClassRepository;
    private final SubclassRepository subclassRepository;
    private final BackgroundRepository backgroundRepository;
    private final EncounterParticipantRepository encounterParticipantRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CharacterResponse createCharacter(CharacterCreateRequest request, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Campaign campaign = null;
        if (request.getCampaignId() != null) {
            campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
        }

        Race raceRef = null;
        if (request.getRaceId() != null) {
            raceRef = raceRepository.findById(request.getRaceId())
                    .orElseThrow(() -> new IllegalArgumentException("Race not found"));
        }

        CharacterClass classRef = null;
        if (request.getClassId() != null) {
            classRef = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        }

        Subclass subclassRef = null;
        if (request.getSubclassId() != null) {
            subclassRef = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));
        }

        Background backgroundRef = null;
        if (request.getBackgroundId() != null) {
            backgroundRef = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new IllegalArgumentException("Background not found"));
        }

        String raceName = raceRef != null ? raceRef.getName() : request.getRace();
        String className = classRef != null ? classRef.getName() : request.getCharacterClass();
        String subclassName = subclassRef != null ? subclassRef.getName() : request.getSubclass();
        String backgroundName = backgroundRef != null ? backgroundRef.getName() : request.getBackground();

        int level = request.getLevel() != null ? request.getLevel() : 1;
        int profBonus = proficiencyBonusForLevel(level);

        Integer speed = request.getSpeed();
        if (speed == null && raceRef != null) {
            speed = extractWalkSpeed(raceRef.getSpeed());
        }
        if (speed == null) speed = 30;

        String spellSlots = request.getSpellSlots();
        Integer spellSaveDc = request.getSpellSaveDc();
        Integer spellAttackBonus = request.getSpellAttackBonus();
        String spellcastingAbility = request.getSpellcastingAbility();

        if (classRef != null && Boolean.TRUE.equals(classRef.getIsSpellcaster()) && spellSlots == null) {
            spellcastingAbility = classRef.getSpellcastingAbility();
            String casterType = deriveCasterType(classRef);
            Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
            entries.put(className, new SpellSlotCalculator.ClassEntry(level, casterType));
            spellSlots = buildSpellSlotsJson(entries);

            int abilityMod = getSpellcastingAbilityMod(spellcastingAbility, request);
            if (spellSaveDc == null) spellSaveDc = 8 + profBonus + abilityMod;
            if (spellAttackBonus == null) spellAttackBonus = profBonus + abilityMod;
        }

        int conMod = abilityMod(request.getConstitution());
        String classFeatureJson = classRef != null ? classRef.getFeatures() : null;
        String subclassFeatureJson = subclassRef != null ? subclassRef.getFeatures() : null;
        int subclassLvl = classRef != null && classRef.getSubclassLevel() != null ? classRef.getSubclassLevel() : 99;
        int hitDice = classRef != null ? classRef.getHitDice() : 8;

        List<LevelUpCalculator.LevelGain> progression = LevelUpCalculator.buildProgression(
                level, classRef != null ? classRef.getId() : null, className,
                hitDice, conMod,
                classFeatureJson, subclassFeatureJson,
                subclassName, subclassLvl);

        int computedHp = LevelUpCalculator.totalHp(progression);
        Integer hpMax = level > 1 ? computedHp : (request.getHpMax() != null ? request.getHpMax() : computedHp);

        List<LevelUpCalculator.FeatureEntry> classFeatures = LevelUpCalculator.allFeatures(progression);
        String mergedFeatures = LevelUpCalculator.mergeFeatures(request.getFeatures(), classFeatures);

        String levelHistory = LevelUpCalculator.serializeLevelHistory(progression);

        String hitDiceMap = request.getHitDiceMap();
        if (hitDiceMap == null && classRef != null) {
            try {
                var hdMap = Map.of(className, Map.of("total", level, "remaining", level, "faces", hitDice));
                hitDiceMap = objectMapper.writeValueAsString(hdMap);
            } catch (Exception ignored) {}
        }

        String multiclassEntries = null;
        if (classRef != null) {
            try {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("classId", classRef.getId().toString());
                entry.put("className", className);
                if (subclassRef != null) {
                    entry.put("subclassId", subclassRef.getId().toString());
                    entry.put("subclassName", subclassName);
                }
                entry.put("level", level);
                multiclassEntries = objectMapper.writeValueAsString(List.of(entry));
            } catch (Exception ignored) {}
        }

        PlayerCharacter character = PlayerCharacter.builder()
                .user(user)
                .campaign(campaign)
                .raceRef(raceRef)
                .classRef(classRef)
                .subclassRef(subclassRef)
                .backgroundRef(backgroundRef)
                .name(request.getName())
                .race(raceName)
                .characterClass(className)
                .subclass(subclassName)
                .level(level)
                .background(backgroundName)
                .alignment(request.getAlignment())
                .abilityScoreMethod(request.getAbilityScoreMethod())
                .racialAbilityBonuses(request.getRacialAbilityBonuses())
                .strength(request.getStrength())
                .dexterity(request.getDexterity())
                .constitution(request.getConstitution())
                .intelligence(request.getIntelligence())
                .wisdom(request.getWisdom())
                .charisma(request.getCharisma())
                .hpMax(hpMax)
                .hpCurrent(hpMax)
                .armourClass(request.getArmourClass() != null ? request.getArmourClass() : 10 + abilityMod(request.getDexterity()))
                .initiativeBonus(request.getInitiativeBonus() != null ? request.getInitiativeBonus() : abilityMod(request.getDexterity()))
                .speed(speed)
                .proficiencyBonus(request.getProficiencyBonus() != null ? request.getProficiencyBonus() : profBonus)
                .savingThrowProficiencies(request.getSavingThrowProficiencies())
                .skillProficiencies(request.getSkillProficiencies())
                .armorProficiencies(request.getArmorProficiencies())
                .weaponProficiencies(request.getWeaponProficiencies())
                .toolProficiencies(request.getToolProficiencies())
                .languageProficiencies(request.getLanguageProficiencies())
                .features(mergedFeatures)
                .damageResistances(request.getDamageResistances())
                .spellsKnown(request.getSpellsKnown())
                .spellSlots(spellSlots)
                .spellSaveDc(spellSaveDc)
                .spellAttackBonus(spellAttackBonus)
                .spellcastingAbility(spellcastingAbility)
                .equipment(request.getEquipment())
                .currency(request.getCurrency() != null ? request.getCurrency() : "{\"cp\":0,\"sp\":0,\"ep\":0,\"gp\":0,\"pp\":0}")
                .hitDiceMap(hitDiceMap)
                .preparedSpells(request.getPreparedSpells())
                .multiclassEntries(multiclassEntries)
                .levelHistory(levelHistory)
                .build();

        if (classRef != null && character.getHitDiceTotal() == null) {
            character.setHitDiceTotal(level + "d" + classRef.getHitDice());
            character.setHitDiceRemaining(character.getHitDiceTotal());
        }

        character = characterRepository.save(character);
        return toResponse(character);
    }

    @Transactional
    public CharacterResponse updateCharacter(UUID characterId, CharacterUpdateRequest request, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

        if (!character.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("You do not own this character");
        }

        if (request.getName() != null) character.setName(request.getName());
        if (request.getRace() != null) character.setRace(request.getRace());
        if (request.getCharacterClass() != null) character.setCharacterClass(request.getCharacterClass());
        if (request.getSubclass() != null) character.setSubclass(request.getSubclass());
        if (request.getLevel() != null) character.setLevel(request.getLevel());
        if (request.getExperiencePoints() != null) character.setExperiencePoints(request.getExperiencePoints());
        if (request.getBackground() != null) character.setBackground(request.getBackground());
        if (request.getAlignment() != null) character.setAlignment(request.getAlignment());
        if (request.getAbilityScoreMethod() != null) character.setAbilityScoreMethod(request.getAbilityScoreMethod());
        if (request.getRacialAbilityBonuses() != null) character.setRacialAbilityBonuses(request.getRacialAbilityBonuses());

        if (request.getRaceId() != null) {
            Race r = raceRepository.findById(request.getRaceId())
                    .orElseThrow(() -> new IllegalArgumentException("Race not found"));
            character.setRaceRef(r);
            character.setRace(r.getName());
        }
        if (request.getClassId() != null) {
            CharacterClass cc = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
            character.setClassRef(cc);
            character.setCharacterClass(cc.getName());
        }
        if (request.getSubclassId() != null) {
            Subclass sc = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));
            character.setSubclassRef(sc);
            character.setSubclass(sc.getName());
        }
        if (request.getBackgroundId() != null) {
            Background bg = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new IllegalArgumentException("Background not found"));
            character.setBackgroundRef(bg);
            character.setBackground(bg.getName());
        }

        if (request.getStrength() != null) character.setStrength(request.getStrength());
        if (request.getDexterity() != null) character.setDexterity(request.getDexterity());
        if (request.getConstitution() != null) character.setConstitution(request.getConstitution());
        if (request.getIntelligence() != null) character.setIntelligence(request.getIntelligence());
        if (request.getWisdom() != null) character.setWisdom(request.getWisdom());
        if (request.getCharisma() != null) character.setCharisma(request.getCharisma());

        if (request.getHpMax() != null) character.setHpMax(request.getHpMax());
        if (request.getHpCurrent() != null) character.setHpCurrent(request.getHpCurrent());
        if (request.getHpTemp() != null) character.setHpTemp(request.getHpTemp());
        if (request.getHitDiceTotal() != null) character.setHitDiceTotal(request.getHitDiceTotal());
        if (request.getHitDiceRemaining() != null) character.setHitDiceRemaining(request.getHitDiceRemaining());
        if (request.getArmourClass() != null) character.setArmourClass(request.getArmourClass());
        if (request.getInitiativeBonus() != null) character.setInitiativeBonus(request.getInitiativeBonus());
        if (request.getSpeed() != null) character.setSpeed(request.getSpeed());
        if (request.getProficiencyBonus() != null) character.setProficiencyBonus(request.getProficiencyBonus());

        if (request.getSavingThrowProficiencies() != null) character.setSavingThrowProficiencies(request.getSavingThrowProficiencies());
        if (request.getSkillProficiencies() != null) character.setSkillProficiencies(request.getSkillProficiencies());
        if (request.getSkillExpertises() != null) character.setSkillExpertises(request.getSkillExpertises());
        if (request.getArmorProficiencies() != null) character.setArmorProficiencies(request.getArmorProficiencies());
        if (request.getWeaponProficiencies() != null) character.setWeaponProficiencies(request.getWeaponProficiencies());
        if (request.getToolProficiencies() != null) character.setToolProficiencies(request.getToolProficiencies());
        if (request.getLanguageProficiencies() != null) character.setLanguageProficiencies(request.getLanguageProficiencies());
        if (request.getDamageResistances() != null) character.setDamageResistances(request.getDamageResistances());
        if (request.getDamageImmunities() != null) character.setDamageImmunities(request.getDamageImmunities());
        if (request.getConditionImmunities() != null) character.setConditionImmunities(request.getConditionImmunities());

        if (request.getFeatures() != null) character.setFeatures(request.getFeatures());
        if (request.getSpellsKnown() != null) character.setSpellsKnown(request.getSpellsKnown());
        if (request.getSpellSlots() != null) character.setSpellSlots(request.getSpellSlots());
        if (request.getSpellSaveDc() != null) character.setSpellSaveDc(request.getSpellSaveDc());
        if (request.getSpellAttackBonus() != null) character.setSpellAttackBonus(request.getSpellAttackBonus());
        if (request.getSpellcastingAbility() != null) character.setSpellcastingAbility(request.getSpellcastingAbility());

        if (request.getEquipment() != null) character.setEquipment(request.getEquipment());
        if (request.getCurrency() != null) character.setCurrency(request.getCurrency());

        if (request.getPersonalityTraits() != null) character.setPersonalityTraits(request.getPersonalityTraits());
        if (request.getIdeals() != null) character.setIdeals(request.getIdeals());
        if (request.getBonds() != null) character.setBonds(request.getBonds());
        if (request.getFlaws() != null) character.setFlaws(request.getFlaws());
        if (request.getNotes() != null) character.setNotes(request.getNotes());
        if (request.getPortraitUrl() != null) character.setPortraitUrl(request.getPortraitUrl());

        if (request.getMulticlassEntries() != null) character.setMulticlassEntries(request.getMulticlassEntries());
        if (request.getPreparedSpells() != null) character.setPreparedSpells(request.getPreparedSpells());
        if (request.getAttunedItems() != null) character.setAttunedItems(request.getAttunedItems());
        if (request.getEquippedItems() != null) character.setEquippedItems(request.getEquippedItems());
        if (request.getHitDiceMap() != null) character.setHitDiceMap(request.getHitDiceMap());
        if (request.getLevelHistory() != null) character.setLevelHistory(request.getLevelHistory());

        if (Boolean.TRUE.equals(request.getClearCampaign())) {
            character.setCampaign(null);
        } else if (request.getCampaignId() != null) {
            Campaign campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
            character.setCampaign(campaign);
        }

        character = characterRepository.save(character);
        return toResponse(character);
    }

    @Transactional
    public LevelUpResponse levelUp(UUID characterId, LevelUpRequest request, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (!character.getUser().getId().equals(userId))
            throw new IllegalArgumentException("You do not own this character");
        if (character.getLevel() >= 20)
            throw new IllegalArgumentException("Character is already at maximum level");

        CharacterClass levelClass;
        UUID levelClassId;
        boolean isNewMulticlass = false;

        if (request != null && request.getClassId() != null) {
            levelClass = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
            levelClassId = levelClass.getId();

            Map<String, Integer> currentLevels = MulticlassValidator.parseMulticlassEntries(character.getMulticlassEntries());
            if (!currentLevels.containsKey(levelClassId.toString())) {
                if (!MulticlassValidator.meetsPrerequisites(levelClass.getMulticlassRequirements(), character))
                    throw new IllegalArgumentException("Character does not meet multiclass prerequisites for " + levelClass.getName());
                if (character.getClassRef() != null &&
                        !MulticlassValidator.meetsPrerequisites(character.getClassRef().getMulticlassRequirements(), character))
                    throw new IllegalArgumentException("Character does not meet exit prerequisites for current class");
                isNewMulticlass = true;
            }
        } else {
            levelClass = character.getClassRef();
            if (levelClass == null)
                throw new IllegalArgumentException("Character has no class reference");
            levelClassId = levelClass.getId();
        }

        int newCharLevel = character.getLevel() + 1;

        Map<String, Integer> classLevels = new LinkedHashMap<>(
                MulticlassValidator.parseMulticlassEntries(character.getMulticlassEntries()));
        int newClassLevel = classLevels.getOrDefault(levelClassId.toString(), 0) + 1;
        classLevels.put(levelClassId.toString(), newClassLevel);

        int hitDice = levelClass.getHitDice();
        int conMod = abilityMod(character.getConstitution());
        int hpGained = LevelUpCalculator.calculateHpGain(newClassLevel, hitDice, conMod);

        Subclass levelSubclass = null;
        if (levelClassId.equals(character.getClassRef() != null ? character.getClassRef().getId() : null)) {
            levelSubclass = character.getSubclassRef();
        }

        String scFeatures = (levelSubclass != null && newClassLevel >= levelClass.getSubclassLevel())
                ? levelSubclass.getFeatures() : null;
        String scName = (levelSubclass != null && newClassLevel >= levelClass.getSubclassLevel())
                ? levelSubclass.getName() : null;

        List<LevelUpCalculator.FeatureEntry> newFeatures = LevelUpCalculator.collectFeaturesForLevel(
                levelClass.getFeatures(), scFeatures, newClassLevel, levelClass.getName(), scName);

        boolean asiAvailable = LevelUpCalculator.isAsiLevel(levelClass.getName(), newClassLevel);
        boolean subclassRequired = newClassLevel == levelClass.getSubclassLevel() && levelSubclass == null;

        character.setLevel(newCharLevel);
        character.setHpMax(character.getHpMax() + hpGained);
        character.setHpCurrent(character.getHpCurrent() + hpGained);
        character.setProficiencyBonus(proficiencyBonusForLevel(newCharLevel));

        appendFeatures(character, newFeatures);
        updateHitDiceMap(character, levelClass.getName(), hitDice, 1);
        character.setHitDiceTotal(buildHitDiceTotal(character));
        character.setHitDiceRemaining(character.getHitDiceTotal());

        updateMulticlassEntries(character, levelClassId, levelClass, levelSubclass, classLevels, isNewMulticlass);

        recalculateSpellSlots(character);

        appendLevelHistory(character, newCharLevel, levelClassId.toString(), levelClass.getName(),
                newClassLevel, hpGained, newFeatures);

        if (isNewMulticlass) {
            applyMulticlassProficiencies(character, levelClass);
        }

        character = characterRepository.save(character);

        List<String> featureNames = newFeatures.stream().map(LevelUpCalculator.FeatureEntry::name).toList();
        LevelUpResponse.PendingChoices choices = LevelUpResponse.PendingChoices.builder()
                .asiAvailable(asiAvailable)
                .subclassRequired(subclassRequired)
                .newFeatures(featureNames)
                .maxSpellLevel(0)
                .build();

        return LevelUpResponse.builder()
                .character(toResponse(character))
                .pendingChoices(choices)
                .build();
    }

    @Transactional
    public CharacterResponse levelDown(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (!character.getUser().getId().equals(userId))
            throw new IllegalArgumentException("You do not own this character");
        if (character.getLevel() <= 1)
            throw new IllegalArgumentException("Character is already at minimum level");

        try {
            List<Map<String, Object>> history = objectMapper.readValue(
                    character.getLevelHistory(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (history.isEmpty())
                throw new IllegalArgumentException("No level history to revert");

            Map<String, Object> lastEntry = history.remove(history.size() - 1);
            int hpGained = lastEntry.get("hpGained") instanceof Number n ? n.intValue() : 0;
            String className = (String) lastEntry.get("className");
            String classId = (String) lastEntry.get("classId");

            character.setLevel(character.getLevel() - 1);
            character.setHpMax(Math.max(1, character.getHpMax() - hpGained));
            character.setHpCurrent(Math.min(character.getHpCurrent(), character.getHpMax()));
            character.setProficiencyBonus(proficiencyBonusForLevel(character.getLevel()));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> featuresGained = (List<Map<String, Object>>)
                    lastEntry.getOrDefault("featuresGained", List.of());
            removeFeatures(character, featuresGained);

            @SuppressWarnings("unchecked")
            Map<String, Object> choices = (Map<String, Object>) lastEntry.getOrDefault("choices", Map.of());
            reverseAsiChoices(character, choices);

            Map<String, Integer> classLevels = new LinkedHashMap<>(
                    MulticlassValidator.parseMulticlassEntries(character.getMulticlassEntries()));
            int currentClassLevel = classLevels.getOrDefault(classId, 1);
            int newClassLevel = currentClassLevel - 1;

            if (newClassLevel <= 0) {
                classLevels.remove(classId);
            } else {
                classLevels.put(classId, newClassLevel);
            }

            CharacterClass classRef = characterClassRepository.findById(UUID.fromString(classId)).orElse(null);
            if (classRef != null) {
                updateHitDiceMap(character, classRef.getName(), classRef.getHitDice(), -1);
            }
            rebuildMulticlassEntries(character, classLevels);

            character.setHitDiceTotal(buildHitDiceTotal(character));
            character.setHitDiceRemaining(character.getHitDiceTotal());

            recalculateSpellSlots(character);

            character.setLevelHistory(objectMapper.writeValueAsString(history));
            character = characterRepository.save(character);
            return toResponse(character);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process level down: " + e.getMessage(), e);
        }
    }

    @Transactional
    public CharacterResponse applyChoices(UUID characterId, ApplyChoicesRequest request, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (!character.getUser().getId().equals(userId))
            throw new IllegalArgumentException("You do not own this character");

        try {
            if (request.getAsi() != null) {
                applyAsi(character, request.getAsi());
            }

            if (request.getSubclassId() != null) {
                Subclass sc = subclassRepository.findById(request.getSubclassId())
                        .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));
                character.setSubclassRef(sc);
                character.setSubclass(sc.getName());

                int classLevel = getCurrentClassLevel(character, character.getClassRef().getId());
                List<LevelUpCalculator.FeatureEntry> scFeatures = LevelUpCalculator.collectFeaturesForLevel(
                        null, sc.getFeatures(), classLevel, character.getCharacterClass(), sc.getName());
                appendFeatures(character, scFeatures);
            }

            recalculateSpellSlots(character);

            character = characterRepository.save(character);
            return toResponse(character);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply choices: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<EligibleClassResponse> getEligibleClasses(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        if (!character.getUser().getId().equals(userId))
            throw new IllegalArgumentException("You do not own this character");

        List<CharacterClass> allClasses = characterClassRepository.findAll();
        return MulticlassValidator.getEligibleClasses(character, allClasses);
    }

    private void applyAsi(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) throws Exception {
        if ("ability".equals(asi.getType()) && asi.getIncreases() != null) {
            for (ApplyChoicesRequest.AbilityIncrease inc : asi.getIncreases()) {
                applyAbilityIncrease(character, inc.getAbility(), inc.getBonus());
            }
        } else if ("feat".equals(asi.getType()) && asi.getFeatName() != null) {
            List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
            features.add(Map.of("name", asi.getFeatName(), "description", "Feat", "source", "Feat"));
            character.setFeatures(objectMapper.writeValueAsString(features));

            if (asi.getFeatAbility() != null && !asi.getFeatAbility().isBlank()) {
                applyAbilityIncrease(character, asi.getFeatAbility(), 1);
            }
        }

        recordAsiInHistory(character, asi);

        String spellcastingAbility = character.getSpellcastingAbility();
        if (spellcastingAbility != null) {
            int profBonus = character.getProficiencyBonus();
            int abilityMod = getAbilityMod(spellcastingAbility, character);
            character.setSpellSaveDc(8 + profBonus + abilityMod);
            character.setSpellAttackBonus(profBonus + abilityMod);
        }
    }

    private void applyAbilityIncrease(PlayerCharacter character, String ability, int bonus) {
        switch (ability.toLowerCase()) {
            case "strength" -> character.setStrength(Math.min(20, character.getStrength() + bonus));
            case "dexterity" -> character.setDexterity(Math.min(20, character.getDexterity() + bonus));
            case "constitution" -> {
                int oldConMod = abilityMod(character.getConstitution());
                character.setConstitution(Math.min(20, character.getConstitution() + bonus));
                int newConMod = abilityMod(character.getConstitution());
                int hpAdjust = (newConMod - oldConMod) * character.getLevel();
                character.setHpMax(character.getHpMax() + hpAdjust);
                character.setHpCurrent(character.getHpCurrent() + hpAdjust);
            }
            case "intelligence" -> character.setIntelligence(Math.min(20, character.getIntelligence() + bonus));
            case "wisdom" -> character.setWisdom(Math.min(20, character.getWisdom() + bonus));
            case "charisma" -> character.setCharisma(Math.min(20, character.getCharisma() + bonus));
        }
    }

    private void reverseAsiChoices(PlayerCharacter character, Map<String, Object> choices) {
        if (choices == null || !choices.containsKey("asi")) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> asi = (Map<String, Object>) choices.get("asi");
            String type = (String) asi.get("type");
            if ("ability".equals(type)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> increases = (List<Map<String, Object>>) asi.get("increases");
                if (increases != null) {
                    for (Map<String, Object> inc : increases) {
                        String ability = (String) inc.get("ability");
                        int bonus = inc.get("bonus") instanceof Number n ? n.intValue() : 0;
                        applyAbilityIncrease(character, ability, -bonus);
                    }
                }
            } else if ("feat".equals(type)) {
                String featName = (String) asi.get("featName");
                if (featName != null) removeFeatureByName(character, featName);
                String featAbility = (String) asi.get("featAbility");
                if (featAbility != null && !featAbility.isBlank()) {
                    applyAbilityIncrease(character, featAbility, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void appendFeatures(PlayerCharacter character, List<LevelUpCalculator.FeatureEntry> newFeatures) {
        try {
            List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
            for (LevelUpCalculator.FeatureEntry f : newFeatures) {
                features.add(Map.of("name", f.name(), "description", f.description(), "source", f.source()));
            }
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception ignored) {}
    }

    private void removeFeatures(PlayerCharacter character, List<Map<String, Object>> featuresGained) {
        try {
            List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
            Set<String> toRemove = new HashSet<>();
            for (Map<String, Object> f : featuresGained) {
                toRemove.add((String) f.get("name"));
            }
            features.removeIf(f -> toRemove.contains(f.get("name")));
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception ignored) {}
    }

    private void removeFeatureByName(PlayerCharacter character, String name) {
        try {
            List<Map<String, Object>> features = parseFeaturesList(character.getFeatures());
            features.removeIf(f -> name.equals(f.get("name")));
            character.setFeatures(objectMapper.writeValueAsString(features));
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFeaturesList(String featuresJson) throws Exception {
        if (featuresJson == null || featuresJson.isBlank()) return new ArrayList<>();
        return new ArrayList<>(objectMapper.readValue(
                featuresJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
    }

    private void updateHitDiceMap(PlayerCharacter character, String className, int faces, int delta) {
        try {
            Map<String, Map<String, Object>> hdMap = character.getHitDiceMap() != null
                    ? objectMapper.readValue(character.getHitDiceMap(), new com.fasterxml.jackson.core.type.TypeReference<>() {})
                    : new LinkedHashMap<>();

            Map<String, Object> entry = hdMap.getOrDefault(className, new LinkedHashMap<>());
            int total = entry.get("total") instanceof Number n ? n.intValue() : 0;
            int remaining = entry.get("remaining") instanceof Number n ? n.intValue() : 0;
            total += delta;
            remaining += delta;
            if (total <= 0) {
                hdMap.remove(className);
            } else {
                entry.put("total", total);
                entry.put("remaining", Math.max(0, remaining));
                entry.put("faces", faces);
                hdMap.put(className, entry);
            }
            character.setHitDiceMap(objectMapper.writeValueAsString(hdMap));
        } catch (Exception ignored) {}
    }

    private String buildHitDiceTotal(PlayerCharacter character) {
        try {
            Map<String, Map<String, Object>> hdMap = character.getHitDiceMap() != null
                    ? objectMapper.readValue(character.getHitDiceMap(), new com.fasterxml.jackson.core.type.TypeReference<>() {})
                    : Map.of();
            return hdMap.entrySet().stream()
                    .map(e -> {
                        int total = e.getValue().get("total") instanceof Number n ? n.intValue() : 0;
                        int faces = e.getValue().get("faces") instanceof Number n ? n.intValue() : 0;
                        return total + "d" + faces;
                    })
                    .reduce((a, b) -> a + " + " + b)
                    .orElse("0");
        } catch (Exception e) {
            return "0";
        }
    }

    private void updateMulticlassEntries(PlayerCharacter character, UUID classId, CharacterClass cc,
                                          Subclass sc, Map<String, Integer> classLevels, boolean isNewMulticlass) {
        try {
            List<Map<String, Object>> entries = character.getMulticlassEntries() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getMulticlassEntries(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}))
                    : new ArrayList<>();

            boolean found = false;
            for (Map<String, Object> entry : entries) {
                if (classId.toString().equals(entry.get("classId"))) {
                    entry.put("level", classLevels.get(classId.toString()));
                    found = true;
                    break;
                }
            }

            if (!found) {
                Map<String, Object> newEntry = new LinkedHashMap<>();
                newEntry.put("classId", classId.toString());
                newEntry.put("className", cc.getName());
                if (sc != null) {
                    newEntry.put("subclassId", sc.getId().toString());
                    newEntry.put("subclassName", sc.getName());
                }
                newEntry.put("level", classLevels.get(classId.toString()));
                entries.add(newEntry);
            }

            character.setMulticlassEntries(objectMapper.writeValueAsString(entries));
        } catch (Exception ignored) {}
    }

    private void rebuildMulticlassEntries(PlayerCharacter character, Map<String, Integer> classLevels) {
        try {
            List<Map<String, Object>> oldEntries = character.getMulticlassEntries() != null
                    ? objectMapper.readValue(character.getMulticlassEntries(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {})
                    : List.of();

            List<Map<String, Object>> newEntries = new ArrayList<>();
            for (Map<String, Object> entry : oldEntries) {
                String id = (String) entry.get("classId");
                if (classLevels.containsKey(id)) {
                    Map<String, Object> updated = new LinkedHashMap<>(entry);
                    updated.put("level", classLevels.get(id));
                    newEntries.add(updated);
                }
            }

            character.setMulticlassEntries(objectMapper.writeValueAsString(newEntries));
        } catch (Exception ignored) {}
    }

    private void recalculateSpellSlots(PlayerCharacter character) {
        try {
            List<Map<String, Object>> entries = character.getMulticlassEntries() != null
                    ? objectMapper.readValue(character.getMulticlassEntries(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {})
                    : List.of();

            Map<String, SpellSlotCalculator.ClassEntry> slotEntries = new LinkedHashMap<>();
            boolean hasAnyCaster = false;
            String primaryCastingAbility = character.getSpellcastingAbility();

            for (Map<String, Object> entry : entries) {
                String classId = (String) entry.get("classId");
                int classLevel = entry.get("level") instanceof Number n ? n.intValue() : 0;
                CharacterClass cc = characterClassRepository.findById(UUID.fromString(classId)).orElse(null);
                if (cc != null && Boolean.TRUE.equals(cc.getIsSpellcaster())) {
                    String casterType = deriveCasterType(cc);
                    slotEntries.put(cc.getName(), new SpellSlotCalculator.ClassEntry(classLevel, casterType));
                    hasAnyCaster = true;
                    if (primaryCastingAbility == null) {
                        primaryCastingAbility = cc.getSpellcastingAbility();
                    }
                }
            }

            if (hasAnyCaster) {
                character.setSpellSlots(buildSpellSlotsJson(slotEntries));
                character.setSpellcastingAbility(primaryCastingAbility);
                if (primaryCastingAbility != null) {
                    int profBonus = character.getProficiencyBonus();
                    int abilityMod = getAbilityMod(primaryCastingAbility, character);
                    character.setSpellSaveDc(8 + profBonus + abilityMod);
                    character.setSpellAttackBonus(profBonus + abilityMod);
                }
            } else {
                character.setSpellSlots(null);
            }
        } catch (Exception ignored) {}
    }

    private void appendLevelHistory(PlayerCharacter character, int charLevel, String classId,
                                     String className, int classLevel, int hpGained,
                                     List<LevelUpCalculator.FeatureEntry> features) {
        try {
            List<Map<String, Object>> history = character.getLevelHistory() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getLevelHistory(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}))
                    : new ArrayList<>();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("characterLevel", charLevel);
            entry.put("classId", classId);
            entry.put("className", className);
            entry.put("classLevel", classLevel);
            entry.put("hpGained", hpGained);
            entry.put("featuresGained", features.stream()
                    .map(f -> Map.of("name", (Object) f.name(), "description", (Object) f.description(), "source", (Object) f.source()))
                    .toList());
            entry.put("choices", new LinkedHashMap<>());

            history.add(entry);
            character.setLevelHistory(objectMapper.writeValueAsString(history));
        } catch (Exception ignored) {}
    }

    private void recordAsiInHistory(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) {
        try {
            List<Map<String, Object>> history = character.getLevelHistory() != null
                    ? new ArrayList<>(objectMapper.readValue(character.getLevelHistory(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}))
                    : new ArrayList<>();

            if (!history.isEmpty()) {
                Map<String, Object> lastEntry = history.get(history.size() - 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> choices = lastEntry.get("choices") instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) lastEntry.get("choices"))
                        : new LinkedHashMap<>();

                Map<String, Object> asiRecord = new LinkedHashMap<>();
                asiRecord.put("type", asi.getType());
                if (asi.getIncreases() != null) {
                    asiRecord.put("increases", asi.getIncreases().stream()
                            .map(i -> Map.of("ability", (Object) i.getAbility(), "bonus", (Object) i.getBonus()))
                            .toList());
                }
                if (asi.getFeatName() != null) asiRecord.put("featName", asi.getFeatName());
                if (asi.getFeatAbility() != null) asiRecord.put("featAbility", asi.getFeatAbility());

                choices.put("asi", asiRecord);
                lastEntry.put("choices", choices);
                character.setLevelHistory(objectMapper.writeValueAsString(history));
            }
        } catch (Exception ignored) {}
    }

    private void applyMulticlassProficiencies(PlayerCharacter character, CharacterClass newClass) {
        if (newClass.getMulticlassProficiencies() == null) return;
        try {
            Map<String, Object> mcProf = objectMapper.readValue(
                    newClass.getMulticlassProficiencies(), new com.fasterxml.jackson.core.type.TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<String> armorGrants = (List<String>) mcProf.get("armor");
            if (armorGrants != null) {
                mergeJsonArray(character, "armor", armorGrants);
            }

            @SuppressWarnings("unchecked")
            List<String> weaponGrants = (List<String>) mcProf.get("weapons");
            if (weaponGrants != null) {
                mergeJsonArray(character, "weapon", weaponGrants);
            }

            @SuppressWarnings("unchecked")
            List<String> toolGrants = (List<String>) mcProf.get("tools");
            if (toolGrants != null) {
                mergeJsonArray(character, "tool", toolGrants);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void mergeJsonArray(PlayerCharacter character, String type, List<String> newItems) {
        try {
            String existing = switch (type) {
                case "armor" -> character.getArmorProficiencies();
                case "weapon" -> character.getWeaponProficiencies();
                case "tool" -> character.getToolProficiencies();
                default -> null;
            };

            List<String> list = existing != null
                    ? new ArrayList<>(objectMapper.readValue(existing, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}))
                    : new ArrayList<>();
            for (String item : newItems) {
                if (!list.contains(item)) list.add(item);
            }
            String json = objectMapper.writeValueAsString(list);

            switch (type) {
                case "armor" -> character.setArmorProficiencies(json);
                case "weapon" -> character.setWeaponProficiencies(json);
                case "tool" -> character.setToolProficiencies(json);
            }
        } catch (Exception ignored) {}
    }

    private int getCurrentClassLevel(PlayerCharacter character, UUID classId) {
        Map<String, Integer> levels = MulticlassValidator.parseMulticlassEntries(character.getMulticlassEntries());
        return levels.getOrDefault(classId.toString(), 1);
    }

    @Transactional
    public void deleteCharacter(UUID characterId, UUID userId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));

        if (!character.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("You do not own this character");
        }

        boolean inActiveCombat = encounterParticipantRepository
                .existsByCharacter_IdAndEncounter_StatusIn(characterId,
                        List.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED, EncounterStatus.PREPARING));

        if (inActiveCombat) {
            throw new IllegalStateException("Cannot delete a character that is in an active encounter");
        }

        character.setIsActive(false);
        characterRepository.save(character);
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> getMyCharacters(UUID userId) {
        return characterRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> getCharactersInCampaign(UUID campaignId, UUID userId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            throw new IllegalArgumentException("You are not a member of this campaign");
        }
        return characterRepository.findByCampaignIdAndIsActiveTrue(campaignId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CharacterResponse getCharacter(UUID characterId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        return toResponse(character);
    }

    private CharacterResponse toResponse(PlayerCharacter c) {
        return CharacterResponse.builder()
                .id(c.getId())
                .userId(c.getUser().getId())
                .ownerDisplayName(c.getUser().getDisplayName())
                .campaignId(c.getCampaign() != null ? c.getCampaign().getId() : null)
                .raceId(c.getRaceRef() != null ? c.getRaceRef().getId() : null)
                .raceName(c.getRaceRef() != null ? c.getRaceRef().getName() : null)
                .classId(c.getClassRef() != null ? c.getClassRef().getId() : null)
                .className(c.getClassRef() != null ? c.getClassRef().getName() : null)
                .subclassId(c.getSubclassRef() != null ? c.getSubclassRef().getId() : null)
                .subclassName(c.getSubclassRef() != null ? c.getSubclassRef().getName() : null)
                .backgroundId(c.getBackgroundRef() != null ? c.getBackgroundRef().getId() : null)
                .backgroundName(c.getBackgroundRef() != null ? c.getBackgroundRef().getName() : null)
                .name(c.getName())
                .race(c.getRace())
                .characterClass(c.getCharacterClass())
                .subclass(c.getSubclass())
                .level(c.getLevel())
                .experiencePoints(c.getExperiencePoints())
                .background(c.getBackground())
                .alignment(c.getAlignment())
                .strength(c.getStrength())
                .dexterity(c.getDexterity())
                .constitution(c.getConstitution())
                .intelligence(c.getIntelligence())
                .wisdom(c.getWisdom())
                .charisma(c.getCharisma())
                .hpMax(c.getHpMax())
                .hpCurrent(c.getHpCurrent())
                .hpTemp(c.getHpTemp())
                .hitDiceTotal(c.getHitDiceTotal())
                .hitDiceRemaining(c.getHitDiceRemaining())
                .armourClass(c.getArmourClass())
                .initiativeBonus(c.getInitiativeBonus())
                .speed(c.getSpeed())
                .proficiencyBonus(c.getProficiencyBonus())
                .savingThrowProficiencies(c.getSavingThrowProficiencies())
                .skillProficiencies(c.getSkillProficiencies())
                .skillExpertises(c.getSkillExpertises())
                .armorProficiencies(c.getArmorProficiencies())
                .weaponProficiencies(c.getWeaponProficiencies())
                .toolProficiencies(c.getToolProficiencies())
                .languageProficiencies(c.getLanguageProficiencies())
                .damageResistances(c.getDamageResistances())
                .damageImmunities(c.getDamageImmunities())
                .conditionImmunities(c.getConditionImmunities())
                .features(c.getFeatures())
                .spellsKnown(c.getSpellsKnown())
                .spellSlots(c.getSpellSlots())
                .spellSaveDc(c.getSpellSaveDc())
                .spellAttackBonus(c.getSpellAttackBonus())
                .spellcastingAbility(c.getSpellcastingAbility())
                .equipment(c.getEquipment())
                .currency(c.getCurrency())
                .personalityTraits(c.getPersonalityTraits())
                .ideals(c.getIdeals())
                .bonds(c.getBonds())
                .flaws(c.getFlaws())
                .notes(c.getNotes())
                .deathSaveSuccesses(c.getDeathSaveSuccesses())
                .deathSaveFailures(c.getDeathSaveFailures())
                .portraitUrl(c.getPortraitUrl())
                .abilityScoreMethod(c.getAbilityScoreMethod())
                .racialAbilityBonuses(c.getRacialAbilityBonuses())
                .multiclassEntries(c.getMulticlassEntries())
                .preparedSpells(c.getPreparedSpells())
                .attunedItems(c.getAttunedItems())
                .equippedItems(c.getEquippedItems())
                .hitDiceMap(c.getHitDiceMap())
                .levelHistory(c.getLevelHistory())
                .isActive(c.getIsActive())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    String buildSpellSlotsJson(Map<String, SpellSlotCalculator.ClassEntry> entries) {
        Map<String, Integer> slots = SpellSlotCalculator.calculateSlots(entries);
        if (slots.isEmpty()) return null;
        try {
            ObjectNode slotsJson = objectMapper.createObjectNode();
            for (var slotEntry : slots.entrySet()) {
                ObjectNode slotObj = objectMapper.createObjectNode();
                slotObj.put("total", slotEntry.getValue());
                slotObj.put("used", 0);
                slotsJson.set(slotEntry.getKey(), slotObj);
            }
            return objectMapper.writeValueAsString(slotsJson);
        } catch (Exception e) {
            return null;
        }
    }

    static int abilityMod(Integer score) {
        if (score == null) return 0;
        return Math.floorDiv(score - 10, 2);
    }

    static int proficiencyBonusForLevel(int level) {
        if (level <= 4) return 2;
        if (level <= 8) return 3;
        if (level <= 12) return 4;
        if (level <= 16) return 5;
        return 6;
    }

    private static String deriveCasterType(CharacterClass classRef) {
        if (Boolean.TRUE.equals(classRef.getIsPactMagic())) return "pact";
        String name = classRef.getName();
        if ("Artificer".equals(name)) return "artificer";
        if ("Paladin".equals(name) || "Ranger".equals(name)) return "half";
        return "full";
    }

    static int getAbilityMod(String ability, PlayerCharacter character) {
        if (ability == null) return 0;
        return switch (ability.toUpperCase()) {
            case "STR" -> abilityMod(character.getStrength());
            case "DEX" -> abilityMod(character.getDexterity());
            case "CON" -> abilityMod(character.getConstitution());
            case "INT" -> abilityMod(character.getIntelligence());
            case "WIS" -> abilityMod(character.getWisdom());
            case "CHA" -> abilityMod(character.getCharisma());
            default -> 0;
        };
    }

    private static int getSpellcastingAbilityMod(String ability, CharacterCreateRequest request) {
        if (ability == null) return 0;
        return switch (ability.toUpperCase()) {
            case "STR" -> abilityMod(request.getStrength());
            case "DEX" -> abilityMod(request.getDexterity());
            case "CON" -> abilityMod(request.getConstitution());
            case "INT" -> abilityMod(request.getIntelligence());
            case "WIS" -> abilityMod(request.getWisdom());
            case "CHA" -> abilityMod(request.getCharisma());
            default -> 0;
        };
    }

    private Integer extractWalkSpeed(String speedJson) {
        if (speedJson == null) return null;
        try {
            if (speedJson.trim().startsWith("{")) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(speedJson);
                if (node.has("walk")) return node.get("walk").asInt();
            }
            return Integer.parseInt(speedJson.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
