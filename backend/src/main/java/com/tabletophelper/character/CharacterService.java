package com.tabletophelper.character;

import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignMemberRepository;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.character.dto.LevelHistoryEntry;
import com.tabletophelper.character.dto.MulticlassEntry;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.reference.*;
import com.tabletophelper.user.User;
import com.tabletophelper.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

@Slf4j
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
    private final FeatRepository featRepository;
    private final FeatEffectResolver featEffectResolver;
    private final CharacterMapper characterMapper;
    private final CharacterJsonHelper jsonHelper;
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

        int conMod = abilityMod(request.getConstitution());

        // Parse multiclass entries if provided
        List<Map<String, Object>> mcEntries = null;
        if (request.getMulticlassClassEntries() != null) {
            try {
                mcEntries = objectMapper.readValue(request.getMulticlassClassEntries(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid multiclass entries JSON: " + e.getMessage());
            }
        }

        if (mcEntries != null) {
            Set<String> seenClassIds = new HashSet<>();
            for (Map<String, Object> mce : mcEntries) {
                String cid = (String) mce.get("classId");
                if (cid != null && !seenClassIds.add(cid)) {
                    throw new IllegalArgumentException("Duplicate class in multiclass entries: " + cid);
                }
            }
        }
        boolean isMulticlass = mcEntries != null && mcEntries.size() > 1;

        List<LevelUpCalculator.LevelGain> progression;
        String multiclassEntries;
        String hitDiceMap = request.getHitDiceMap();
        StringBuilder hitDiceTotalBuilder = new StringBuilder();
        String spellSlots = request.getSpellSlots();
        Integer spellSaveDc = request.getSpellSaveDc();
        Integer spellAttackBonus = request.getSpellAttackBonus();
        String spellcastingAbility = request.getSpellcastingAbility();
        String characterClassName = className;

        if (isMulticlass) {
            List<LevelUpCalculator.ClassInput> classInputs = new ArrayList<>();
            List<LinkedHashMap<String, Object>> mcEntryList = new ArrayList<>();
            Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries = new LinkedHashMap<>();
            Map<String, Map<String, Object>> hdMapBuilder = new LinkedHashMap<>();

            for (Map<String, Object> mce : mcEntries) {
                UUID mcClassId = UUID.fromString((String) mce.get("classId"));
                int mcLevel = ((Number) mce.get("level")).intValue();
                String mcSubclassId = mce.get("subclassId") != null ? (String) mce.get("subclassId") : null;

                CharacterClass mcClassRef = characterClassRepository.findById(mcClassId)
                        .orElseThrow(() -> new IllegalArgumentException("Class not found: " + mcClassId));
                Subclass mcSubclassRef = null;
                if (mcSubclassId != null && !mcSubclassId.isEmpty()) {
                    mcSubclassRef = subclassRepository.findById(UUID.fromString(mcSubclassId)).orElse(null);
                }

                String mcClassName = mcClassRef.getName();
                String mcSubName = mcSubclassRef != null ? mcSubclassRef.getName() : null;
                int mcSubLevel = mcClassRef.getSubclassLevel() != null ? mcClassRef.getSubclassLevel() : 99;

                classInputs.add(new LevelUpCalculator.ClassInput(
                        mcClassId, mcClassName, mcLevel, mcClassRef.getHitDice(),
                        mcClassRef.getFeatures(),
                        mcSubclassRef != null ? mcSubclassRef.getFeatures() : null,
                        mcSubName, mcSubLevel));

                var mcEntry = new LinkedHashMap<String, Object>();
                mcEntry.put("classId", mcClassId.toString());
                mcEntry.put("className", mcClassName);
                if (mcSubclassRef != null) {
                    mcEntry.put("subclassId", mcSubclassRef.getId().toString());
                    mcEntry.put("subclassName", mcSubName);
                }
                mcEntry.put("level", mcLevel);
                mcEntryList.add(mcEntry);

                hdMapBuilder.put(mcClassName, Map.of("total", mcLevel, "remaining", mcLevel, "faces", mcClassRef.getHitDice()));
                if (hitDiceTotalBuilder.length() > 0) hitDiceTotalBuilder.append(" + ");
                hitDiceTotalBuilder.append(mcLevel).append("d").append(mcClassRef.getHitDice());

                boolean isMcClassCaster = Boolean.TRUE.equals(mcClassRef.getIsSpellcaster());
                boolean isMcThirdCaster = mcSubName != null && THIRD_CASTER_SUBCLASSES.contains(mcSubName) && mcLevel >= 3;
                if (isMcClassCaster || isMcThirdCaster) {
                    String casterType = deriveCasterType(mcClassRef, mcSubName);
                    if (!"none".equals(casterType)) {
                        spellSlotEntries.put(mcClassName, new SpellSlotCalculator.ClassEntry(mcLevel, casterType));
                    }
                }
            }

            progression = LevelUpCalculator.buildMulticlassProgression(classInputs, conMod);

            try { multiclassEntries = objectMapper.writeValueAsString(mcEntryList); }
            catch (Exception e) { multiclassEntries = null; }

            if (hitDiceMap == null) {
                try { hitDiceMap = objectMapper.writeValueAsString(hdMapBuilder); }
                catch (Exception e) { log.warn("Failed to serialize hit dice map during character creation", e); }
            }

            if (!spellSlotEntries.isEmpty() && spellSlots == null) {
                spellSlots = buildSpellSlotsJson(spellSlotEntries);
            }

            for (Map<String, Object> mce : mcEntries) {
                UUID mcCasterId = UUID.fromString((String) mce.get("classId"));
                String mceSubclassName = (String) mce.get("subclassName");
                int mceLevel = mce.get("level") instanceof Number n ? n.intValue() : 0;
                CharacterClass mcCasterRef = characterClassRepository.findById(mcCasterId).orElse(null);
                if (mcCasterRef == null) continue;
                boolean isClassCaster = Boolean.TRUE.equals(mcCasterRef.getIsSpellcaster());
                boolean isThirdCaster = mceSubclassName != null && THIRD_CASTER_SUBCLASSES.contains(mceSubclassName) && mceLevel >= 3;
                if ((isClassCaster || isThirdCaster) && spellcastingAbility == null) {
                    spellcastingAbility = isClassCaster ? mcCasterRef.getSpellcastingAbility() : "INT";
                    int abilityModVal = getSpellcastingAbilityMod(spellcastingAbility, request);
                    spellSaveDc = 8 + profBonus + abilityModVal;
                    spellAttackBonus = profBonus + abilityModVal;
                    break;
                }
            }

            characterClassName = classInputs.stream()
                    .map(LevelUpCalculator.ClassInput::className)
                    .reduce((a, b) -> a + " / " + b).orElse(className);

        } else {
            String classFeatureJson = classRef != null ? classRef.getFeatures() : null;
            String subclassFeatureJson = subclassRef != null ? subclassRef.getFeatures() : null;
            int subclassLvl = classRef != null && classRef.getSubclassLevel() != null ? classRef.getSubclassLevel() : 99;
            int hitDice = classRef != null ? classRef.getHitDice() : 8;

            progression = LevelUpCalculator.buildProgression(
                    level, classRef != null ? classRef.getId() : null, className,
                    hitDice, conMod,
                    classFeatureJson, subclassFeatureJson,
                    subclassName, subclassLvl);

            if (hitDiceMap == null && classRef != null) {
                try {
                    var hdMap = Map.of(className, Map.of("total", level, "remaining", level, "faces", hitDice));
                    hitDiceMap = objectMapper.writeValueAsString(hdMap);
                } catch (Exception e) { log.warn("Failed to serialize hit dice map for single-class creation", e); }
            }

            if (classRef != null) {
                hitDiceTotalBuilder.append(level).append("d").append(hitDice);
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
                } catch (Exception e) { multiclassEntries = null; }
            } else {
                multiclassEntries = null;
            }

            boolean isSingleClassCaster = classRef != null && Boolean.TRUE.equals(classRef.getIsSpellcaster());
            boolean isSingleThirdCaster = subclassName != null && THIRD_CASTER_SUBCLASSES.contains(subclassName) && level >= 3;
            if ((isSingleClassCaster || isSingleThirdCaster) && spellSlots == null) {
                if (spellcastingAbility == null) {
                    spellcastingAbility = isSingleClassCaster ? classRef.getSpellcastingAbility() : "INT";
                }
                String casterType = deriveCasterType(classRef, subclassName);
                if (!"none".equals(casterType)) {
                    Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
                    entries.put(className, new SpellSlotCalculator.ClassEntry(level, casterType));
                    spellSlots = buildSpellSlotsJson(entries);
                }

                int abilityModVal = getSpellcastingAbilityMod(spellcastingAbility, request);
                if (spellSaveDc == null) spellSaveDc = 8 + profBonus + abilityModVal;
                if (spellAttackBonus == null) spellAttackBonus = profBonus + abilityModVal;
            }
        }

        int computedHp = LevelUpCalculator.totalHp(progression);
        Integer hpMax = level > 1 || isMulticlass ? computedHp : (request.getHpMax() != null ? request.getHpMax() : computedHp);

        List<LevelUpCalculator.FeatureEntry> classFeatures = LevelUpCalculator.allFeatures(progression);
        String mergedFeatures = LevelUpCalculator.mergeFeatures(request.getFeatures(), classFeatures);

        String levelHistory = LevelUpCalculator.serializeLevelHistory(progression);

        PlayerCharacter character = PlayerCharacter.builder()
                .user(user)
                .campaign(campaign)
                .raceRef(raceRef)
                .classRef(classRef)
                .subclassRef(subclassRef)
                .backgroundRef(backgroundRef)
                .name(request.getName())
                .race(raceName)
                .characterClass(characterClassName)
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
                .skillExpertises(request.getSkillExpertises())
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
                .multiclassEntries(multiclassEntries)
                .levelHistory(levelHistory)
                .build();

        if (character.getHitDiceTotal() == null && hitDiceTotalBuilder.length() > 0) {
            character.setHitDiceTotal(hitDiceTotalBuilder.toString());
            character.setHitDiceRemaining(hitDiceTotalBuilder.toString());
        } else if (classRef != null && character.getHitDiceTotal() == null) {
            character.setHitDiceTotal(level + "d" + classRef.getHitDice());
            character.setHitDiceRemaining(character.getHitDiceTotal());
        }

        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
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
        if (request.getAttunedItems() != null) character.setAttunedItems(request.getAttunedItems());
        if (request.getEquippedItems() != null) character.setEquippedItems(request.getEquippedItems());
        if (request.getHitDiceMap() != null) character.setHitDiceMap(request.getHitDiceMap());
        if (request.getLevelHistory() != null) character.setLevelHistory(request.getLevelHistory());
        if (request.getFeatResources() != null) character.setFeatResources(request.getFeatResources());

        if (Boolean.TRUE.equals(request.getClearCampaign())) {
            character.setCampaign(null);
        } else if (request.getCampaignId() != null) {
            Campaign campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found"));
            character.setCampaign(campaign);
        }

        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
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
        } else {
            UUID existingScId = jsonHelper.getSubclassIdFromMulticlassEntries(character, levelClassId);
            if (existingScId != null) {
                levelSubclass = subclassRepository.findById(existingScId).orElse(null);
            }
        }

        String scFeatures = (levelSubclass != null && newClassLevel >= levelClass.getSubclassLevel())
                ? levelSubclass.getFeatures() : null;
        String scName = (levelSubclass != null && newClassLevel >= levelClass.getSubclassLevel())
                ? levelSubclass.getName() : null;

        List<LevelUpCalculator.FeatureEntry> newFeatures = LevelUpCalculator.collectFeaturesForLevel(
                levelClass.getFeatures(), scFeatures, newClassLevel, levelClass.getName(), scName);

        boolean asiAvailable = LevelUpCalculator.isAsiLevel(levelClass.getName(), newClassLevel);
        boolean subclassRequired = newClassLevel == levelClass.getSubclassLevel() && levelSubclass == null;
        boolean expertiseAvailable = isExpertiseLevel(levelClass.getName(), newClassLevel);
        int expertiseSlots = expertiseAvailable ? 2 : 0;

        character.setLevel(newCharLevel);
        character.setHpMax(character.getHpMax() + hpGained);
        character.setHpCurrent(character.getHpCurrent() + hpGained);
        character.setProficiencyBonus(proficiencyBonusForLevel(newCharLevel));

        jsonHelper.appendFeatures(character, newFeatures);
        jsonHelper.updateHitDiceMap(character,levelClass.getName(), hitDice, 1);
        character.setHitDiceTotal(jsonHelper.buildHitDiceTotal(character));
        character.setHitDiceRemaining(character.getHitDiceTotal());

        jsonHelper.updateMulticlassEntries(character,levelClassId, levelClass, levelSubclass, classLevels, isNewMulticlass);

        recalculateSpellSlots(character);

        jsonHelper.appendLevelHistory(character,newCharLevel, levelClassId.toString(), levelClass.getName(),
                newClassLevel, hpGained, newFeatures);

        if (isNewMulticlass) {
            applyMulticlassProficiencies(character, levelClass);
        }

        character = characterRepository.save(character);

        boolean isCasterClass = Boolean.TRUE.equals(levelClass.getIsSpellcaster());
        boolean spellSelectionNeeded = isCasterClass;
        String spellSelectionType = null;
        if (spellSelectionNeeded) {
            boolean isKnownCaster = List.of("Bard", "Ranger", "Sorcerer", "Warlock").contains(levelClass.getName());
            spellSelectionType = isKnownCaster ? "known" : "prepared";
        }

        List<String> featureNames = newFeatures.stream().map(LevelUpCalculator.FeatureEntry::name).toList();
        LevelUpResponse.PendingChoices choices = LevelUpResponse.PendingChoices.builder()
                .asiAvailable(asiAvailable)
                .subclassRequired(subclassRequired)
                .expertiseAvailable(expertiseAvailable)
                .expertiseCount(expertiseSlots)
                .spellSelectionNeeded(spellSelectionNeeded)
                .spellSelectionType(spellSelectionType)
                .newFeatures(featureNames)
                .maxSpellLevel(0)
                .build();

        return LevelUpResponse.builder()
                .character(characterMapper.toResponse(character))
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
            List<LevelHistoryEntry> history = new ArrayList<>(objectMapper.readValue(
                    character.getLevelHistory(), new com.fasterxml.jackson.core.type.TypeReference<List<LevelHistoryEntry>>() {}));
            if (history.isEmpty())
                throw new IllegalArgumentException("No level history to revert");

            LevelHistoryEntry lastEntry = history.remove(history.size() - 1);
            int hpGained = lastEntry.hpGained();
            String className = lastEntry.className();
            String classId = lastEntry.classId();

            character.setLevel(character.getLevel() - 1);
            character.setHpMax(Math.max(1, character.getHpMax() - hpGained));
            character.setHpCurrent(Math.min(character.getHpCurrent(), character.getHpMax()));
            character.setProficiencyBonus(proficiencyBonusForLevel(character.getLevel()));

            List<LevelHistoryEntry.FeatureRecord> featuresGained = lastEntry.featuresGained() != null
                    ? lastEntry.featuresGained() : List.of();
            jsonHelper.removeFeatures(character, featuresGained);

            Map<String, Object> choices = lastEntry.choices() != null ? lastEntry.choices() : Map.of();
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
                jsonHelper.updateHitDiceMap(character,classRef.getName(), classRef.getHitDice(), -1);
            }
            jsonHelper.rebuildMulticlassEntries(character, classLevels);

            character.setHitDiceTotal(jsonHelper.buildHitDiceTotal(character));
            character.setHitDiceRemaining(character.getHitDiceTotal());

            recalculateSpellSlots(character);

            character.setLevelHistory(objectMapper.writeValueAsString(history));
            character = characterRepository.save(character);
            return characterMapper.toResponse(character);
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

            if (request.getExpertiseSkills() != null && !request.getExpertiseSkills().isEmpty()) {
                List<String> currentExpertise = character.getSkillExpertises() != null
                        ? objectMapper.readValue(character.getSkillExpertises(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})
                        : new ArrayList<>();
                for (String skill : request.getExpertiseSkills()) {
                    if (!currentExpertise.contains(skill)) currentExpertise.add(skill);
                }
                character.setSkillExpertises(objectMapper.writeValueAsString(currentExpertise));
            }

            if (request.getSubclassId() != null) {
                Subclass sc = subclassRepository.findById(request.getSubclassId())
                        .orElseThrow(() -> new IllegalArgumentException("Subclass not found"));

                UUID targetClassId = request.getClassId() != null ? request.getClassId()
                        : (character.getClassRef() != null ? character.getClassRef().getId() : null);
                boolean isPrimaryClass = character.getClassRef() != null
                        && targetClassId != null && targetClassId.equals(character.getClassRef().getId());

                if (isPrimaryClass) {
                    character.setSubclassRef(sc);
                    character.setSubclass(sc.getName());
                }

                jsonHelper.updateMulticlassEntrySubclass(character,targetClassId, sc);

                CharacterClass targetClass = targetClassId != null
                        ? characterClassRepository.findById(targetClassId).orElse(null) : null;
                String targetClassName = targetClass != null ? targetClass.getName() : character.getCharacterClass();
                int classLevel = getCurrentClassLevel(character, targetClassId);
                List<LevelUpCalculator.FeatureEntry> scFeatures = LevelUpCalculator.collectFeaturesForLevel(
                        null, sc.getFeatures(), classLevel, targetClassName, sc.getName());
                jsonHelper.appendFeatures(character, scFeatures);
            }

            recalculateSpellSlots(character);

            character = characterRepository.save(character);
            return characterMapper.toResponse(character);
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
            jsonHelper.recordAsiInHistory(character, asi);
        } else if ("feat".equals(asi.getType())) {
            if (asi.getFeatId() != null) {
                Feat feat = featRepository.findById(asi.getFeatId())
                        .orElseThrow(() -> new IllegalArgumentException("Feat not found"));
                FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, asi);
                jsonHelper.recordFeatInHistory(character,asi, feat.getName(), applied);
            } else if (asi.getFeatName() != null) {
                var features = jsonHelper.parseFeaturesList(character.getFeatures());
                features.add(new LevelHistoryEntry.FeatureRecord(asi.getFeatName(), "Feat", "Feat"));
                character.setFeatures(objectMapper.writeValueAsString(features));
                if (asi.getFeatAbility() != null && !asi.getFeatAbility().isBlank()) {
                    applyAbilityIncrease(character, asi.getFeatAbility(), 1);
                }
                jsonHelper.recordAsiInHistory(character, asi);
            }
        }

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
                @SuppressWarnings("unchecked")
                Map<String, Object> appliedEffects = (Map<String, Object>) asi.get("appliedEffects");
                if (appliedEffects != null) {
                    featEffectResolver.reverseFeatEffects(character, appliedEffects);
                } else {
                    String featName = (String) asi.get("featName");
                    if (featName != null) jsonHelper.removeFeatureByName(character, featName);
                    String featAbility = (String) asi.get("featAbility");
                    if (featAbility != null && !featAbility.isBlank()) {
                        applyAbilityIncrease(character, featAbility, -1);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to reverse ASI choices for character {}", character.getId(), e);
        }
    }


    private void recalculateSpellSlots(PlayerCharacter character) {
        try {
            List<MulticlassEntry> entries = character.getMulticlassEntries() != null
                    ? objectMapper.readValue(character.getMulticlassEntries(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<MulticlassEntry>>() {})
                    : List.of();

            Map<String, SpellSlotCalculator.ClassEntry> slotEntries = new LinkedHashMap<>();
            boolean hasAnyCaster = false;
            String primaryCastingAbility = character.getSpellcastingAbility();

            for (MulticlassEntry entry : entries) {
                String classId = entry.classId();
                int classLevel = entry.level();
                String subclassName = entry.subclassName();
                CharacterClass cc = characterClassRepository.findById(UUID.fromString(classId)).orElse(null);
                if (cc == null) continue;
                boolean isClassCaster = Boolean.TRUE.equals(cc.getIsSpellcaster());
                boolean isThirdCaster = subclassName != null && THIRD_CASTER_SUBCLASSES.contains(subclassName) && classLevel >= 3;
                if (isClassCaster || isThirdCaster) {
                    String casterType = deriveCasterType(cc, subclassName);
                    if (!"none".equals(casterType)) {
                        slotEntries.put(cc.getName(), new SpellSlotCalculator.ClassEntry(classLevel, casterType));
                        hasAnyCaster = true;
                    }
                    if (primaryCastingAbility == null) {
                        primaryCastingAbility = isClassCaster ? cc.getSpellcastingAbility() : "INT";
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
        } catch (Exception e) {
            log.error("Failed to recalculate spell slots for character {} — spell slots may be stale", character.getId(), e);
            throw new IllegalStateException("Failed to recalculate spell slots: " + e.getMessage(), e);
        }
    }

    private void applyMulticlassProficiencies(PlayerCharacter character, CharacterClass newClass) {
        if (newClass.getMulticlassProficiencies() == null) return;
        try {
            Map<String, Object> mcProf = objectMapper.readValue(
                    newClass.getMulticlassProficiencies(), new com.fasterxml.jackson.core.type.TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<String> armorGrants = (List<String>) mcProf.get("armor");
            if (armorGrants != null) {
                jsonHelper.mergeJsonArray(character,"armor", armorGrants);
            }

            @SuppressWarnings("unchecked")
            List<String> weaponGrants = (List<String>) mcProf.get("weapons");
            if (weaponGrants != null) {
                jsonHelper.mergeJsonArray(character,"weapon", weaponGrants);
            }

            @SuppressWarnings("unchecked")
            List<String> toolGrants = (List<String>) mcProf.get("tools");
            if (toolGrants != null) {
                jsonHelper.mergeJsonArray(character,"tool", toolGrants);
            }
        } catch (Exception e) {
            log.error("Failed to apply multiclass proficiencies from {} for character {}",
                    newClass.getName(), character.getId(), e);
        }
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
                .map(characterMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> getCharactersInCampaign(UUID campaignId, UUID userId) {
        if (!campaignMemberRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            throw new IllegalArgumentException("You are not a member of this campaign");
        }
        return characterRepository.findByCampaignIdAndIsActiveTrue(campaignId).stream()
                .map(characterMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CharacterResponse getCharacter(UUID characterId) {
        PlayerCharacter character = characterRepository.findById(characterId)
                .orElseThrow(() -> new IllegalArgumentException("Character not found"));
        return characterMapper.toResponse(character);
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

    static boolean isExpertiseLevel(String className, int classLevel) {
        if ("Rogue".equals(className)) return classLevel == 1 || classLevel == 6;
        if ("Bard".equals(className)) return classLevel == 3 || classLevel == 10;
        return false;
    }

    static int proficiencyBonusForLevel(int level) {
        if (level <= 4) return 2;
        if (level <= 8) return 3;
        if (level <= 12) return 4;
        if (level <= 16) return 5;
        return 6;
    }

    private static final Set<String> THIRD_CASTER_SUBCLASSES = Set.of("Eldritch Knight", "Arcane Trickster");

    private static String deriveCasterType(CharacterClass classRef, String subclassName) {
        if (Boolean.TRUE.equals(classRef.getIsPactMagic())) return "pact";
        String name = classRef.getName();
        if ("Artificer".equals(name)) return "artificer";
        if ("Paladin".equals(name) || "Ranger".equals(name)) return "half";
        if (!Boolean.TRUE.equals(classRef.getIsSpellcaster()) && subclassName != null && THIRD_CASTER_SUBCLASSES.contains(subclassName)) return "third";
        if (Boolean.TRUE.equals(classRef.getIsSpellcaster())) return "full";
        return "none";
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
                var node = objectMapper.readTree(speedJson);
                if (node.has("walk")) return node.get("walk").asInt();
            }
            return Integer.parseInt(speedJson.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
