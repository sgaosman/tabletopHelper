package com.tabletophelper.character;

import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ResourcePoolEntry;
import com.tabletophelper.character.state.SpellSlot;
import com.tabletophelper.encounter.EncounterParticipantRepository;
import com.tabletophelper.encounter.EncounterStatus;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.reference.*;
import com.tabletophelper.resourcepool.ResourcePoolService;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.SpellSlotCalculator;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import static com.tabletophelper.character.CharacterUpdates.copy;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final UserService userService;
    private final CampaignRepository campaignRepository;
    private final RaceRepository raceRepository;
    private final CharacterClassRepository characterClassRepository;
    private final SubclassRepository subclassRepository;
    private final BackgroundRepository backgroundRepository;
    private final EncounterParticipantRepository encounterParticipantRepository;
    private final FeatRepository featRepository;
    private final FeatEffectResolver featEffectResolver;
    private final CharacterMapper characterMapper;
    private final ObjectMapper objectMapper;
    private final ResourcePoolService resourcePoolService;
    private final AccessPolicy accessPolicy;

    @Transactional
    public CharacterResponse createCharacter(CharacterCreateRequest request, UUID userId) {
        User user = userService.getUser(userId);

        Campaign campaign = null;
        if (request.getCampaignId() != null) {
            campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new ValidationException("Campaign not found"));
            accessPolicy.requireMember(campaign.getId(), userId);
        }

        Race raceRef = null;
        if (request.getRaceId() != null) {
            raceRef = raceRepository.findById(request.getRaceId())
                    .orElseThrow(() -> new ValidationException("Race not found"));
        }

        CharacterClass classRef = null;
        if (request.getClassId() != null) {
            classRef = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new ValidationException("Class not found"));
        }

        Subclass subclassRef = null;
        if (request.getSubclassId() != null) {
            subclassRef = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new ValidationException("Subclass not found"));
        }

        Background backgroundRef = null;
        if (request.getBackgroundId() != null) {
            backgroundRef = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new ValidationException("Background not found"));
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

        List<ClassEntry> mcEntries = request.getMulticlassClassEntries();
        if (mcEntries != null) {
            Set<UUID> seenClassIds = new HashSet<>();
            for (ClassEntry mce : mcEntries) {
                UUID cid = mce.classId();
                if (cid != null && !seenClassIds.add(cid)) {
                    throw new ValidationException("Duplicate class in multiclass entries: " + cid);
                }
            }
        }
        boolean isMulticlass = mcEntries != null && mcEntries.size() > 1;

        List<LevelUpCalculator.LevelGain> progression;
        List<ClassEntry> multiclassEntries;
        Map<String, HitDicePool> hitDicePools = request.getHitDice();
        Map<String, SpellSlot> spellSlots = request.getSpellSlots();
        Integer spellSaveDc = request.getSpellSaveDc();
        Integer spellAttackBonus = request.getSpellAttackBonus();
        String spellcastingAbility = request.getSpellcastingAbility();
        String characterClassName = className;

        if (isMulticlass) {
            List<LevelUpCalculator.ClassInput> classInputs = new ArrayList<>();
            List<ClassEntry> mcEntryList = new ArrayList<>();
            Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries = new LinkedHashMap<>();
            Map<String, HitDicePool> hitDiceBuilder = new LinkedHashMap<>();

            for (ClassEntry mce : mcEntries) {
                UUID mcClassId = mce.classId();
                int mcLevel = mce.level();
                UUID mcSubclassId = mce.subclassId();

                CharacterClass mcClassRef = characterClassRepository.findById(mcClassId)
                        .orElseThrow(() -> new ValidationException("Class not found: " + mcClassId));
                Subclass mcSubclassRef = null;
                if (mcSubclassId != null) {
                    mcSubclassRef = subclassRepository.findById(mcSubclassId).orElse(null);
                }

                String mcClassName = mcClassRef.getName();
                String mcSubName = mcSubclassRef != null ? mcSubclassRef.getName() : null;
                int mcSubLevel = mcClassRef.getSubclassLevel() != null ? mcClassRef.getSubclassLevel() : 99;

                classInputs.add(new LevelUpCalculator.ClassInput(
                        mcClassId, mcClassName, mcLevel, mcClassRef.getHitDice(),
                        mcClassRef.getFeatures(),
                        mcSubclassRef != null ? mcSubclassRef.getFeatures() : null,
                        mcSubName, mcSubLevel));

                mcEntryList.add(new ClassEntry(mcClassId, mcClassName,
                        mcSubclassRef != null ? mcSubclassRef.getId() : null, mcSubName, mcLevel));

                hitDiceBuilder.put(mcClassName, new HitDicePool(mcClassRef.getHitDice(), mcLevel, mcLevel));

                boolean isMcClassCaster = Boolean.TRUE.equals(mcClassRef.getIsSpellcaster());
                boolean isMcThirdCaster = mcSubName != null && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(mcSubName) && mcLevel >= 3;
                if (isMcClassCaster || isMcThirdCaster) {
                    String casterType = SpellSlotCalculator.casterType(mcClassName, isMcClassCaster,
                            Boolean.TRUE.equals(mcClassRef.getIsPactMagic()), mcSubName);
                    if (!"none".equals(casterType)) {
                        spellSlotEntries.put(mcClassName, new SpellSlotCalculator.ClassEntry(mcLevel, casterType));
                    }
                }
            }

            progression = LevelUpCalculator.buildMulticlassProgression(classInputs, conMod);

            multiclassEntries = mcEntryList;

            if (hitDicePools == null) {
                hitDicePools = hitDiceBuilder;
            }

            if (!spellSlotEntries.isEmpty() && spellSlots == null) {
                spellSlots = buildSpellSlots(spellSlotEntries);
            }

            for (ClassEntry mce : mcEntries) {
                UUID mcCasterId = mce.classId();
                String mceSubclassName = mce.subclassName();
                int mceLevel = mce.level();
                CharacterClass mcCasterRef = characterClassRepository.findById(mcCasterId).orElse(null);
                if (mcCasterRef == null) continue;
                boolean isClassCaster = Boolean.TRUE.equals(mcCasterRef.getIsSpellcaster());
                boolean isThirdCaster = mceSubclassName != null && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(mceSubclassName) && mceLevel >= 3;
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

            if (hitDicePools == null && classRef != null) {
                hitDicePools = new LinkedHashMap<>(Map.of(className, new HitDicePool(hitDice, level, level)));
            }

            if (classRef != null) {
                multiclassEntries = List.of(new ClassEntry(classRef.getId(), className,
                        subclassRef != null ? subclassRef.getId() : null, subclassRef != null ? subclassName : null,
                        level));
            } else {
                multiclassEntries = null;
            }

            boolean isSingleClassCaster = classRef != null && Boolean.TRUE.equals(classRef.getIsSpellcaster());
            boolean isSingleThirdCaster = subclassName != null && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(subclassName) && level >= 3;
            if ((isSingleClassCaster || isSingleThirdCaster) && spellSlots == null) {
                if (spellcastingAbility == null) {
                    spellcastingAbility = isSingleClassCaster ? classRef.getSpellcastingAbility() : "INT";
                }
                String casterType = SpellSlotCalculator.casterType(className, isSingleClassCaster,
                        classRef != null && Boolean.TRUE.equals(classRef.getIsPactMagic()), subclassName);
                if (!"none".equals(casterType)) {
                    Map<String, SpellSlotCalculator.ClassEntry> entries = new LinkedHashMap<>();
                    entries.put(className, new SpellSlotCalculator.ClassEntry(level, casterType));
                    spellSlots = buildSpellSlots(entries);
                }

                int abilityModVal = getSpellcastingAbilityMod(spellcastingAbility, request);
                if (spellSaveDc == null) spellSaveDc = 8 + profBonus + abilityModVal;
                if (spellAttackBonus == null) spellAttackBonus = profBonus + abilityModVal;
            }
        }

        int computedHp = LevelUpCalculator.totalHp(progression);
        Integer hpMax = level > 1 || isMulticlass ? computedHp : (request.getHpMax() != null ? request.getHpMax() : computedHp);

        List<LevelUpCalculator.FeatureEntry> classFeatures = LevelUpCalculator.allFeatures(progression);
        List<Feature> mergedFeatures = LevelUpCalculator.mergeFeatures(request.getFeatures(), classFeatures);

        List<LevelHistoryEntry> levelHistory = LevelUpCalculator.levelHistory(progression);

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
                .currency(request.getCurrency() != null ? request.getCurrency() : Currency.NONE)
                .hitDice(hitDicePools)
                .multiclassEntries(multiclassEntries)
                .levelHistory(levelHistory)
                .build();

        resourcePoolService.syncPoolsForCharacter(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    @Transactional
    public CharacterResponse updateCharacter(UUID characterId, CharacterUpdateRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

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
                    .orElseThrow(() -> new ValidationException("Race not found"));
            character.setRaceRef(r);
            character.setRace(r.getName());
        }
        if (request.getClassId() != null) {
            CharacterClass cc = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new ValidationException("Class not found"));
            character.setClassRef(cc);
            character.setCharacterClass(cc.getName());
        }
        if (request.getSubclassId() != null) {
            Subclass sc = subclassRepository.findById(request.getSubclassId())
                    .orElseThrow(() -> new ValidationException("Subclass not found"));
            character.setSubclassRef(sc);
            character.setSubclass(sc.getName());
        }
        if (request.getBackgroundId() != null) {
            Background bg = backgroundRepository.findById(request.getBackgroundId())
                    .orElseThrow(() -> new ValidationException("Background not found"));
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
        if (request.getHitDice() != null) character.setHitDice(request.getHitDice());
        if (request.getLevelHistory() != null) character.setLevelHistory(request.getLevelHistory());
        if (request.getFeatResources() != null) character.setFeatResources(request.getFeatResources());
        if (request.getResourcePools() != null) character.setResourcePools(request.getResourcePools());

        if (Boolean.TRUE.equals(request.getClearCampaign())) {
            character.setCampaign(null);
        } else if (request.getCampaignId() != null) {
            Campaign campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new ValidationException("Campaign not found"));
            accessPolicy.requireMember(campaign.getId(), userId);
            character.setCampaign(campaign);
        }

        // Recalculate spell stats if ability scores changed and spellcasting is present
        if (character.getSpellcastingAbility() != null &&
                (request.getStrength() != null || request.getDexterity() != null || request.getConstitution() != null ||
                 request.getIntelligence() != null || request.getWisdom() != null || request.getCharisma() != null)) {
            int profBonus = character.getProficiencyBonus();
            int abilityModVal = getAbilityMod(character.getSpellcastingAbility(), character);
            character.setSpellSaveDc(8 + profBonus + abilityModVal);
            character.setSpellAttackBonus(profBonus + abilityModVal);
        }

        resourcePoolService.syncPoolsForCharacter(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    @Transactional
    public LevelUpResponse levelUp(UUID characterId, LevelUpRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);
        if (character.getLevel() >= 20)
            throw new ConflictException("Character is already at maximum level");

        CharacterClass levelClass;
        UUID levelClassId;
        boolean isNewMulticlass = false;

        if (request != null && request.getClassId() != null) {
            levelClass = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new ValidationException("Class not found"));
            levelClassId = levelClass.getId();

            Map<UUID, Integer> currentLevels = MulticlassValidator.classLevels(character.getMulticlassEntries());
            if (!currentLevels.containsKey(levelClassId)) {
                if (!MulticlassValidator.meetsPrerequisites(levelClass.getMulticlassRequirements(), character))
                    throw new ConflictException("Character does not meet multiclass prerequisites for " + levelClass.getName());
                if (character.getClassRef() != null &&
                        !MulticlassValidator.meetsPrerequisites(character.getClassRef().getMulticlassRequirements(), character))
                    throw new ConflictException("Character does not meet exit prerequisites for current class");
                isNewMulticlass = true;
            }
        } else {
            levelClass = character.getClassRef();
            if (levelClass == null)
                throw new ConflictException("Character has no class reference");
            levelClassId = levelClass.getId();
        }

        int newCharLevel = character.getLevel() + 1;

        Map<UUID, Integer> classLevels = new LinkedHashMap<>(
                MulticlassValidator.classLevels(character.getMulticlassEntries()));
        int newClassLevel = classLevels.getOrDefault(levelClassId, 0) + 1;
        classLevels.put(levelClassId, newClassLevel);

        int hitDice = levelClass.getHitDice();
        int conMod = abilityMod(character.getConstitution());
        int hpGained = LevelUpCalculator.calculateHpGain(newClassLevel, hitDice, conMod);

        Subclass levelSubclass = null;
        if (levelClassId.equals(character.getClassRef() != null ? character.getClassRef().getId() : null)) {
            levelSubclass = character.getSubclassRef();
        } else {
            UUID existingScId = CharacterUpdates.subclassIdOf(character, levelClassId);
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

        CharacterUpdates.appendFeatures(character, newFeatures);
        CharacterUpdates.updateHitDice(character, levelClass.getName(), hitDice, 1);

        CharacterUpdates.updateClassEntry(character, levelClassId, levelClass, levelSubclass, classLevels);

        recalculateSpellSlots(character);

        CharacterUpdates.appendLevelHistory(character, newCharLevel, levelClassId, levelClass.getName(),
                newClassLevel, hpGained, newFeatures);

        if (isNewMulticlass) {
            applyMulticlassProficiencies(character, levelClass);
        }

        resourcePoolService.syncPoolsForCharacter(character);
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
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);
        if (character.getLevel() <= 1)
            throw new ConflictException("Character is already at minimum level");

        try {
            List<LevelHistoryEntry> history = copy(character.getLevelHistory());
            if (history.isEmpty())
                throw new ConflictException("No level history to revert");

            LevelHistoryEntry lastEntry = history.remove(history.size() - 1);
            int hpGained = lastEntry.hpGained();
            UUID classId = lastEntry.classId();

            character.setLevel(character.getLevel() - 1);
            character.setHpMax(Math.max(1, character.getHpMax() - hpGained));
            character.setHpCurrent(Math.min(character.getHpCurrent(), character.getHpMax()));
            character.setProficiencyBonus(proficiencyBonusForLevel(character.getLevel()));

            List<Feature> featuresGained = lastEntry.featuresGained() != null
                    ? lastEntry.featuresGained() : List.of();
            CharacterUpdates.removeFeatures(character, featuresGained);

            reverseAsiChoices(character, lastEntry.choices());

            Map<UUID, Integer> classLevels = new LinkedHashMap<>(
                    MulticlassValidator.classLevels(character.getMulticlassEntries()));
            int currentClassLevel = classLevels.getOrDefault(classId, 1);
            int newClassLevel = currentClassLevel - 1;

            if (newClassLevel <= 0) {
                classLevels.remove(classId);
            } else {
                classLevels.put(classId, newClassLevel);
            }

            CharacterClass classRef = characterClassRepository.findById(classId).orElse(null);
            if (classRef != null) {
                CharacterUpdates.updateHitDice(character, classRef.getName(), classRef.getHitDice(), -1);
            }
            CharacterUpdates.rebuildClassEntries(character, classLevels);

            recalculateSpellSlots(character);

            character.setLevelHistory(history);
            character = characterRepository.save(character);
            return characterMapper.toResponse(character);
        } catch (ConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to process level down: " + e.getMessage(), e);
        }
    }

    @Transactional
    public CharacterResponse applyChoices(UUID characterId, ApplyChoicesRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        try {
            if (request.getAsi() != null) {
                applyAsi(character, request.getAsi());
            }

            if (request.getExpertiseSkills() != null && !request.getExpertiseSkills().isEmpty()) {
                character.setSkillExpertises(
                        CharacterUpdates.merged(character.getSkillExpertises(), request.getExpertiseSkills()));
            }

            if (request.getSubclassId() != null) {
                Subclass sc = subclassRepository.findById(request.getSubclassId())
                        .orElseThrow(() -> new ValidationException("Subclass not found"));

                UUID targetClassId = request.getClassId() != null ? request.getClassId()
                        : (character.getClassRef() != null ? character.getClassRef().getId() : null);
                boolean isPrimaryClass = character.getClassRef() != null
                        && targetClassId != null && targetClassId.equals(character.getClassRef().getId());

                if (isPrimaryClass) {
                    character.setSubclassRef(sc);
                    character.setSubclass(sc.getName());
                }

                CharacterUpdates.setClassEntrySubclass(character, targetClassId, sc);

                CharacterClass targetClass = targetClassId != null
                        ? characterClassRepository.findById(targetClassId).orElse(null) : null;
                String targetClassName = targetClass != null ? targetClass.getName() : character.getCharacterClass();
                int classLevel = getCurrentClassLevel(character, targetClassId);
                List<LevelUpCalculator.FeatureEntry> scFeatures = LevelUpCalculator.collectFeaturesForLevel(
                        null, sc.getFeatures(), classLevel, targetClassName, sc.getName());
                CharacterUpdates.appendFeatures(character, scFeatures);
            }

            recalculateSpellSlots(character);

            resourcePoolService.syncPoolsForCharacter(character);
            character = characterRepository.save(character);
            return characterMapper.toResponse(character);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply choices: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<EligibleClassResponse> getEligibleClasses(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        List<CharacterClass> allClasses = characterClassRepository.findAll();
        return MulticlassValidator.getEligibleClasses(character, allClasses);
    }

    private void applyAsi(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) throws Exception {
        if ("ability".equals(asi.getType()) && asi.getIncreases() != null) {
            for (ApplyChoicesRequest.AbilityIncrease inc : asi.getIncreases()) {
                applyAbilityIncrease(character, inc.getAbility(), inc.getBonus());
            }
            CharacterUpdates.recordAsiInHistory(character, asi);
        } else if ("feat".equals(asi.getType())) {
            if (asi.getFeatId() != null) {
                Feat feat = featRepository.findById(asi.getFeatId())
                        .orElseThrow(() -> new ValidationException("Feat not found"));
                FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, asi);
                CharacterUpdates.recordFeatInHistory(character, asi, feat.getName(), applied);
            } else if (asi.getFeatName() != null) {
                List<Feature> features = copy(character.getFeatures());
                features.add(new Feature(asi.getFeatName(), "Feat", "Feat"));
                character.setFeatures(features);
                if (asi.getFeatAbility() != null && !asi.getFeatAbility().isBlank()) {
                    applyAbilityIncrease(character, asi.getFeatAbility(), 1);
                }
                CharacterUpdates.recordAsiInHistory(character, asi);
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

    private void reverseAsiChoices(PlayerCharacter character, LevelChoices choices) {
        if (choices == null || choices.asi() == null) return;
        try {
            AbilityScoreImprovement asi = choices.asi();
            if ("ability".equals(asi.type())) {
                if (asi.increases() != null) {
                    for (AbilityBonus inc : asi.increases()) {
                        applyAbilityIncrease(character, inc.ability(), -inc.bonus());
                    }
                }
            } else if ("feat".equals(asi.type())) {
                if (asi.appliedEffects() != null) {
                    featEffectResolver.reverseFeatEffects(character, asi.appliedEffects());
                } else {
                    if (asi.featName() != null) CharacterUpdates.removeFeatureByName(character, asi.featName());
                    String featAbility = asi.featAbility();
                    if (featAbility != null && !featAbility.isBlank()) {
                        applyAbilityIncrease(character, featAbility, -1);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to reverse ASI choices for character {}", character.getId(), e);
        }
    }

    /**
     * Calculator entries for each of the character's casting classes, read from its class entries.
     * Shared by level changes and the recompute-spell-slots maintenance job (D153).
     */
    public Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries(PlayerCharacter character) {
        return collectSlotEntries(character).entries();
    }

    private record SlotEntries(Map<String, SpellSlotCalculator.ClassEntry> entries, String firstCastingAbility) {}

    private SlotEntries collectSlotEntries(PlayerCharacter character) {
        Map<String, SpellSlotCalculator.ClassEntry> slotEntries = new LinkedHashMap<>();
        String firstCastingAbility = null;

        for (ClassEntry entry : CharacterUpdates.orEmpty(character.getMulticlassEntries())) {
            int classLevel = entry.level();
            String subclassName = entry.subclassName();
            CharacterClass cc = characterClassRepository.findById(entry.classId()).orElse(null);
            if (cc == null) continue;
            boolean isClassCaster = Boolean.TRUE.equals(cc.getIsSpellcaster());
            boolean isThirdCaster = subclassName != null && SpellSlotCalculator.THIRD_CASTER_SUBCLASSES.contains(subclassName) && classLevel >= 3;
            if (isClassCaster || isThirdCaster) {
                String casterType = SpellSlotCalculator.casterType(cc.getName(), isClassCaster,
                        Boolean.TRUE.equals(cc.getIsPactMagic()), subclassName);
                if (!"none".equals(casterType)) {
                    slotEntries.put(cc.getName(), new SpellSlotCalculator.ClassEntry(classLevel, casterType));
                }
                if (firstCastingAbility == null) {
                    firstCastingAbility = isClassCaster ? cc.getSpellcastingAbility() : "INT";
                }
            }
        }
        return new SlotEntries(slotEntries, firstCastingAbility);
    }

    private void recalculateSpellSlots(PlayerCharacter character) {
        try {
            SlotEntries collected = collectSlotEntries(character);
            Map<String, SpellSlotCalculator.ClassEntry> slotEntries = collected.entries();
            String primaryCastingAbility = character.getSpellcastingAbility() != null
                    ? character.getSpellcastingAbility()
                    : collected.firstCastingAbility();

            if (!slotEntries.isEmpty()) {
                character.setSpellSlots(buildSpellSlots(slotEntries));
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
                character.setArmorProficiencies(CharacterUpdates.merged(character.getArmorProficiencies(), armorGrants));
            }

            @SuppressWarnings("unchecked")
            List<String> weaponGrants = (List<String>) mcProf.get("weapons");
            if (weaponGrants != null) {
                character.setWeaponProficiencies(CharacterUpdates.merged(character.getWeaponProficiencies(), weaponGrants));
            }

            @SuppressWarnings("unchecked")
            List<String> toolGrants = (List<String>) mcProf.get("tools");
            if (toolGrants != null) {
                character.setToolProficiencies(CharacterUpdates.merged(character.getToolProficiencies(), toolGrants));
            }
        } catch (Exception e) {
            log.error("Failed to apply multiclass proficiencies from {} for character {}",
                    newClass.getName(), character.getId(), e);
        }
    }

    private int getCurrentClassLevel(PlayerCharacter character, UUID classId) {
        return MulticlassValidator.classLevels(character.getMulticlassEntries()).getOrDefault(classId, 1);
    }

    @Transactional
    public CharacterResponse shortRest(UUID characterId, ShortRestRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        int conMod = abilityMod(character.getConstitution());

        // Spend hit dice for healing
        if (request != null && request.getHitDiceToSpend() != null && !request.getHitDiceToSpend().isEmpty()) {
            Map<String, HitDicePool> hitDice = character.getHitDice() != null
                    ? new LinkedHashMap<>(character.getHitDice()) : new LinkedHashMap<>();
            int totalHeal = 0;

            for (var entry : request.getHitDiceToSpend().entrySet()) {
                String className = entry.getKey();
                int count = entry.getValue();
                HitDicePool pool = hitDice.get(className);
                if (pool == null)
                    throw new ValidationException("No hit dice for class: " + className);
                if (pool.remaining() < count)
                    throw new ConflictException("Not enough hit dice remaining for " + className);

                int healPerDie = (pool.dieSize() / 2 + 1) + conMod;
                totalHeal += count * healPerDie;
                hitDice.put(className, new HitDicePool(pool.dieSize(), pool.total(), pool.remaining() - count));
            }

            character.setHpCurrent(Math.min(character.getHpMax(), character.getHpCurrent() + totalHeal));
            character.setHitDice(hitDice);
        }

        // Reset pact slots
        resetPactSlots(character);

        // Reset short rest resource pools (and keep feat_resources for backward compat)
        resetFeatResources(character, true);
        resetResourcePools(character, "shortRest");

        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    @Transactional
    public CharacterResponse longRest(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        // Restore full HP
        character.setHpCurrent(character.getHpMax());

        // Restore all spell slots
        resetAllSpellSlots(character);

        // Recover hit dice: floor(total/2), min 1
        recoverHitDice(character);

        // Reset all feat resources (backward compat) and resource pools
        resetFeatResources(character, false);
        resetResourcePools(character, "longRest");

        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    private void resetPactSlots(PlayerCharacter character) {
        resetSpellSlots(character, true);
    }

    private void resetAllSpellSlots(PlayerCharacter character) {
        resetSpellSlots(character, false);
    }

    // Each slot level's used count goes back to 0, or only the pact slots'
    private static void resetSpellSlots(PlayerCharacter character, boolean pactOnly) {
        if (character.getSpellSlots() == null) return;
        Map<String, SpellSlot> slots = new LinkedHashMap<>();
        character.getSpellSlots().forEach((key, slot) -> slots.put(key,
                slot != null && (!pactOnly || key.startsWith("pact_")) ? new SpellSlot(slot.max(), 0) : slot));
        character.setSpellSlots(slots);
    }

    private void recoverHitDice(PlayerCharacter character) {
        if (character.getHitDice() == null) return;
        Map<String, HitDicePool> hitDice = new LinkedHashMap<>(character.getHitDice());

        int totalDice = hitDice.values().stream().mapToInt(HitDicePool::total).sum();
        int recovery = Math.max(1, totalDice / 2);

        for (var entry : hitDice.entrySet()) {
            HitDicePool pool = entry.getValue();
            int missing = pool.total() - pool.remaining();
            int classRecovery = Math.min(missing, recovery);
            if (classRecovery > 0) {
                entry.setValue(new HitDicePool(pool.dieSize(), pool.total(), pool.remaining() + classRecovery));
                recovery -= classRecovery;
            }
            if (recovery <= 0) break;
        }

        character.setHitDice(hitDice);
    }

    private void resetFeatResources(PlayerCharacter character, boolean shortRestOnly) {
        if (character.getFeatResources() == null) return;
        List<FeatResourceEntry> updated = new ArrayList<>();
        for (FeatResourceEntry r : character.getFeatResources()) {
            if (!shortRestOnly || "shortRest".equals(r.resetOn())) {
                updated.add(new FeatResourceEntry(r.featName(), r.name(), r.maxUses(), r.maxUses(), r.resetOn()));
            } else {
                updated.add(r);
            }
        }
        character.setFeatResources(updated);
    }

    /**
     * Resets resource pools matching the given trigger (shortRest / longRest).
     * Operates on the character's {@code resource_pools} JSONB column.
     */
    private void resetResourcePools(PlayerCharacter character, String resetTrigger) {
        try {
            List<ResourcePoolEntry> pools = CharacterUpdates.orEmpty(character.getResourcePools());
            if (pools.isEmpty()) return;
            Map<String, Integer> context = resourcePoolService.buildCharacterContext(character);
            character.setResourcePools(new ArrayList<>(resourcePoolService.resetPools(pools, resetTrigger, context).pools()));
        } catch (Exception e) {
            log.warn("Failed to reset resource pools for character {}", character.getId(), e);
        }
    }

    @Transactional
    public void deleteCharacter(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        boolean inActiveCombat = encounterParticipantRepository
                .existsByCharacter_IdAndEncounter_StatusIn(characterId,
                        List.of(EncounterStatus.ACTIVE, EncounterStatus.PAUSED, EncounterStatus.PREPARING));

        if (inActiveCombat) {
            throw new ConflictException("Cannot delete a character that is in an active encounter");
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
        accessPolicy.requireDm(campaignId, userId);
        return characterRepository.findByCampaignIdAndIsActiveTrue(campaignId).stream()
                .map(characterMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CharacterResponse getCharacter(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        UUID campaignId = character.getCampaign() != null ? character.getCampaign().getId() : null;
        accessPolicy.requireOwnerOrDm(character.getUser().getId(), campaignId, userId);

        return characterMapper.toResponse(character);
    }

    private PlayerCharacter loadCharacter(UUID characterId) {
        return characterRepository.findById(characterId)
                .orElseThrow(() -> new NotFoundException("Character not found"));
    }

    // Every slot unused at its new maximum, or null for none
    Map<String, SpellSlot> buildSpellSlots(Map<String, SpellSlotCalculator.ClassEntry> entries) {
        Map<String, Integer> slots = SpellSlotCalculator.calculateSlots(entries);
        if (slots.isEmpty()) return null;
        Map<String, SpellSlot> spellSlots = new LinkedHashMap<>();
        slots.forEach((key, max) -> spellSlots.put(key, new SpellSlot(max, 0)));
        return spellSlots;
    }

    static int abilityMod(Integer score) {
        if (score == null) return 0;
        return Ability.modifier(score);
    }

    static boolean isExpertiseLevel(String className, int classLevel) {
        if ("Rogue".equals(className)) return classLevel == 1 || classLevel == 6;
        if ("Bard".equals(className)) return classLevel == 3 || classLevel == 10;
        return false;
    }

    static int proficiencyBonusForLevel(int level) {
        return ProficiencyBonus.forLevel(level);
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
