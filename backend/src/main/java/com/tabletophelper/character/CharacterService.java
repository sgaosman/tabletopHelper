package com.tabletophelper.character;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.FeatResourceEntry;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.HitDicePool;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ProficienciesGained;
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
import com.tabletophelper.rules.character.ClassProgression;
import com.tabletophelper.rules.character.HitPoints;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.NotFoundException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.tabletophelper.character.CharacterUpdates.copy;
import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Characters: creating them, levelling them up and down, the choices levels bring, rests and the interim PUT. After
 * every change CharacterStats works out the Derived Stats again, writes their stored copies and syncs the pools
 * (D158, D167), so nothing here does that arithmetic itself.
 */
@Service
@RequiredArgsConstructor
public class CharacterService {

    // Primal Champion, the barbarian's 20th-level feature, adds 4 to Strength and Constitution (PHB p.49)
    private static final String BARBARIAN = "Barbarian";
    private static final int PRIMAL_CHAMPION_LEVEL = 20;
    private static final int PRIMAL_CHAMPION_INCREASE = 4;
    // A class with no subclass level never asks for one
    private static final int NO_SUBCLASS_LEVEL = 99;

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
    private final CharacterStats characterStats;

    // ── Creating ─────────────────────────────────────────────────

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
        String characterClassName = className;

        if (isMulticlass) {
            if (mcEntries.stream().anyMatch(e -> e.level() < 1)) {
                throw new ValidationException("Each class needs at least one level");
            }
            int classLevels = mcEntries.stream().mapToInt(ClassEntry::level).sum();
            if (classLevels != level) {
                throw new ValidationException("The class levels add up to " + classLevels + ", but the character is level "
                        + level);
            }
            List<LevelUpCalculator.ClassInput> classInputs = new ArrayList<>();
            List<ClassEntry> mcEntryList = new ArrayList<>();

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

                classInputs.add(new LevelUpCalculator.ClassInput(
                        mcClassId, mcClassName, mcLevel, mcClassRef.getHitDice(),
                        mcClassRef.getFeatures(),
                        mcSubclassRef != null ? mcSubclassRef.getFeatures() : null,
                        mcSubName, subclassLevel(mcClassRef)));

                mcEntryList.add(new ClassEntry(mcClassId, mcClassName,
                        mcSubclassRef != null ? mcSubclassRef.getId() : null, mcSubName, mcLevel));
            }

            progression = LevelUpCalculator.buildMulticlassProgression(classInputs);
            multiclassEntries = mcEntryList;
            characterClassName = classInputs.stream()
                    .map(LevelUpCalculator.ClassInput::className)
                    .reduce((a, b) -> a + " / " + b).orElse(className);
        } else {
            progression = LevelUpCalculator.buildProgression(
                    level, classRef != null ? classRef.getId() : null, className,
                    classRef != null ? classRef.getHitDice() : 8,
                    classRef != null ? classRef.getFeatures() : null,
                    subclassRef != null ? subclassRef.getFeatures() : null,
                    subclassName, classRef != null ? subclassLevel(classRef) : NO_SUBCLASS_LEVEL);

            multiclassEntries = classRef != null
                    ? List.of(new ClassEntry(classRef.getId(), className,
                            subclassRef != null ? subclassRef.getId() : null, subclassRef != null ? subclassName : null,
                            level))
                    : null;
        }

        List<Feature> mergedFeatures = LevelUpCalculator.mergeFeatures(request.getFeatures(),
                LevelUpCalculator.allFeatures(progression));

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
                // Armour isn't modelled, so the armour class starts at 10 + DEX and changes only through the DM (D158)
                .armourClass(10 + abilityMod(request.getDexterity()))
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
                .spellcastingAbility(chosenSpellcastingAbility(request.getSpellcastingAbility()))
                .equipment(request.getEquipment())
                .currency(request.getCurrency() != null ? request.getCurrency() : Currency.NONE)
                .multiclassEntries(multiclassEntries)
                .levelHistory(LevelUpCalculator.levelHistory(progression))
                .build();

        recordCreationHpGains(character);
        characterStats.apply(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    // The ability race and feat spells use, abbreviated; null for none
    private static String chosenSpellcastingAbility(String ability) {
        if (ability == null || ability.isBlank()) return null;
        return CharacterUpdates.ability(ability).name();
    }

    // What each level a character is created with added to the hit point maximum, its first included
    private void recordCreationHpGains(PlayerCharacter character) {
        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        int previous = 0;
        for (int i = 0; i < history.size(); i++) {
            int maximum = characterStats.hitPointMaximum(character, history.subList(0, i + 1));
            history.set(i, history.get(i).withHpGained(maximum - previous));
            previous = maximum;
        }
        character.setLevelHistory(history);
    }

    // ── The interim PUT ──────────────────────────────────────────

    @Transactional
    public CharacterResponse updateCharacter(UUID characterId, CharacterUpdateRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        if (request.getName() != null) character.setName(request.getName());
        if (request.getRace() != null) character.setRace(request.getRace());
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

        if (request.getHpCurrent() != null) character.setHpCurrent(request.getHpCurrent());
        if (request.getHpTemp() != null) character.setHpTemp(request.getHpTemp());

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

        if (request.getSpellsKnown() != null) character.setSpellsKnown(request.getSpellsKnown());
        if (request.getSpellSlots() != null) {
            character.setSpellSlots(withUsedCounts(character.getSpellSlots(), request.getSpellSlots()));
        }

        if (request.getEquipment() != null) character.setEquipment(request.getEquipment());
        if (request.getCurrency() != null) character.setCurrency(request.getCurrency());

        if (request.getPersonalityTraits() != null) character.setPersonalityTraits(request.getPersonalityTraits());
        if (request.getIdeals() != null) character.setIdeals(request.getIdeals());
        if (request.getBonds() != null) character.setBonds(request.getBonds());
        if (request.getFlaws() != null) character.setFlaws(request.getFlaws());
        if (request.getNotes() != null) character.setNotes(request.getNotes());
        if (request.getPortraitUrl() != null) character.setPortraitUrl(request.getPortraitUrl());

        if (request.getHitDice() != null) {
            character.setHitDice(withRemainingCounts(character.getHitDice(), request.getHitDice()));
        }
        if (request.getFeatResources() != null) character.setFeatResources(request.getFeatResources());
        if (request.getResourcePools() != null) {
            character.setResourcePools(withCurrentUses(character.getResourcePools(), request.getResourcePools()));
        }

        if (Boolean.TRUE.equals(request.getClearCampaign())) {
            character.setCampaign(null);
        } else if (request.getCampaignId() != null) {
            Campaign campaign = campaignRepository.findById(request.getCampaignId())
                    .orElseThrow(() -> new ValidationException("Campaign not found"));
            accessPolicy.requireMember(campaign.getId(), userId);
            character.setCampaign(campaign);
        }

        characterStats.apply(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    // The slots with the used counts asked for, each between 0 and the slot's maximum; a slot the character
    // doesn't have is ignored
    private static Map<String, SpellSlot> withUsedCounts(Map<String, SpellSlot> slots, Map<String, SpellSlot> asked) {
        if (slots == null) return null;
        Map<String, SpellSlot> updated = new LinkedHashMap<>();
        slots.forEach((key, slot) -> {
            SpellSlot wanted = asked.get(key);
            updated.put(key, wanted != null ? new SpellSlot(slot.max(), clamp(wanted.used(), slot.max())) : slot);
        });
        return updated;
    }

    // The Hit Dice with the remaining counts asked for, each between 0 and the class's total
    private static Map<String, HitDicePool> withRemainingCounts(Map<String, HitDicePool> hitDice,
                                                                Map<String, HitDicePool> asked) {
        if (hitDice == null) return null;
        Map<String, HitDicePool> updated = new LinkedHashMap<>();
        hitDice.forEach((className, pool) -> {
            HitDicePool wanted = asked.get(className);
            updated.put(className, wanted != null
                    ? new HitDicePool(pool.dieSize(), pool.total(), clamp(wanted.remaining(), pool.total())) : pool);
        });
        return updated;
    }

    // The pools with the uses asked for, each between 0 and the pool's maximum; a pool the character doesn't have is
    // ignored
    private static List<ResourcePoolEntry> withCurrentUses(List<ResourcePoolEntry> pools,
                                                           List<ResourcePoolEntry> asked) {
        if (pools == null) return null;
        Map<String, Integer> wanted = new HashMap<>();
        for (ResourcePoolEntry pool : asked) {
            if (pool != null && pool.poolId() != null) wanted.putIfAbsent(pool.poolId(), pool.currentUses());
        }
        return pools.stream()
                .map(pool -> wanted.containsKey(pool.poolId())
                        ? pool.withCurrentUses(clamp(wanted.get(pool.poolId()), pool.maxUses())) : pool)
                .toList();
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, maximum));
    }

    // ── Levelling up ─────────────────────────────────────────────

    @Transactional
    public LevelUpResponse levelUp(UUID characterId, LevelUpRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);
        if (character.getLevel() >= 20)
            throw new ConflictException("Character is already at maximum level");

        CharacterClass levelClass;
        if (request != null && request.getClassId() != null) {
            levelClass = characterClassRepository.findById(request.getClassId())
                    .orElseThrow(() -> new ValidationException("Class not found"));
        } else {
            levelClass = character.getClassRef();
            if (levelClass == null)
                throw new ConflictException("Character has no class reference");
        }
        UUID levelClassId = levelClass.getId();

        Map<UUID, Integer> classLevels = new LinkedHashMap<>(
                MulticlassValidator.classLevels(character.getMulticlassEntries()));
        boolean isNewClass = !classLevels.containsKey(levelClassId);
        if (isNewClass) {
            // A new class needs its own prerequisites and those of every class the character has (PHB p.163)
            List<String> unmet = MulticlassValidator.unmetPrerequisites(character, levelClass, currentClasses(character));
            if (!unmet.isEmpty())
                throw new ConflictException("Character does not meet multiclass prerequisites for "
                        + String.join(" and ", unmet));
        }

        int newCharLevel = character.getLevel() + 1;
        int newClassLevel = classLevels.getOrDefault(levelClassId, 0) + 1;
        classLevels.put(levelClassId, newClassLevel);

        Subclass levelSubclass = null;
        if (levelClassId.equals(character.getClassRef() != null ? character.getClassRef().getId() : null)) {
            levelSubclass = character.getSubclassRef();
        } else {
            UUID existingScId = CharacterUpdates.subclassIdOf(character, levelClassId);
            if (existingScId != null) {
                levelSubclass = subclassRepository.findById(existingScId).orElse(null);
            }
        }

        boolean subclassFeatures = levelSubclass != null && newClassLevel >= subclassLevel(levelClass);
        List<LevelUpCalculator.FeatureEntry> newFeatures = LevelUpCalculator.collectFeaturesForLevel(
                levelClass.getFeatures(), subclassFeatures ? levelSubclass.getFeatures() : null, newClassLevel,
                levelClass.getName(), subclassFeatures ? levelSubclass.getName() : null);

        boolean asiAvailable = ClassProgression.isAsiLevel(levelClass.getName(), newClassLevel);
        boolean subclassRequired = newClassLevel == subclassLevel(levelClass) && levelSubclass == null;
        int expertiseCount = ClassProgression.expertiseChoices(levelClass.getName(), newClassLevel);

        character.setLevel(newCharLevel);
        CharacterUpdates.appendFeatures(character, newFeatures);
        CharacterUpdates.updateClassEntry(character, levelClassId, levelClass, levelSubclass, classLevels);
        ProficienciesGained proficienciesGained = isNewClass ? applyMulticlassProficiencies(character, levelClass) : null;
        List<AbilityBonus> abilityIncreases = applyPrimalChampion(character, levelClass, newClassLevel);
        CharacterUpdates.appendLevelHistory(character, newCharLevel, levelClassId, levelClass.getName(), newClassLevel,
                HitPoints.fixedHitDieResult(newCharLevel, levelClass.getHitDice()), newFeatures, proficienciesGained,
                abilityIncreases);

        int oldHpMax = character.getHpMax();
        DerivedStats stats = characterStats.apply(character);
        CharacterUpdates.recordHpGained(character, stats.hpMax() - oldHpMax);
        character = characterRepository.save(character);

        // The class's own table says whether this level brings spells to learn or prepare, an Eldritch Knight's and
        // an Arcane Trickster's included, and how high they go (PHB p.164)
        ClassSpellcasting casting = stats.spellcasting().stream()
                .filter(c -> c.className().equals(levelClass.getName()))
                .findFirst()
                .orElse(null);
        boolean spellSelectionNeeded = casting != null
                && (casting.highestSpellLevel() > 0 || casting.cantripsKnown() > 0);
        String spellSelectionType = spellSelectionNeeded
                ? (casting.preparedSpells() != null ? "prepared" : "known") : null;

        List<String> featureNames = newFeatures.stream().map(LevelUpCalculator.FeatureEntry::name).toList();
        LevelUpResponse.PendingChoices choices = LevelUpResponse.PendingChoices.builder()
                .asiAvailable(asiAvailable)
                .subclassRequired(subclassRequired)
                .expertiseAvailable(expertiseCount > 0)
                .expertiseCount(expertiseCount)
                .spellSelectionNeeded(spellSelectionNeeded)
                .spellSelectionType(spellSelectionType)
                .newFeatures(featureNames)
                .maxSpellLevel(casting != null ? casting.highestSpellLevel() : 0)
                .build();

        return LevelUpResponse.builder()
                .character(characterMapper.toResponse(character))
                .pendingChoices(choices)
                .build();
    }

    // The classes the character has, as the class data has them
    private List<CharacterClass> currentClasses(PlayerCharacter character) {
        List<CharacterClass> classes = new ArrayList<>();
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            classes.add(characterClassRepository.findById(entry.classId())
                    .orElseThrow(() -> new ConflictException("The class " + entry.className()
                            + " that the character has can't be found")));
        }
        return classes;
    }

    private static int subclassLevel(CharacterClass characterClass) {
        return characterClass.getSubclassLevel() != null ? characterClass.getSubclassLevel() : NO_SUBCLASS_LEVEL;
    }

    /**
     * The armour, weapon and tool proficiencies multiclassing into a class grants (PHB p.164), less those the
     * character has, which the level records so that levelling down removes them (D167). A skill to choose isn't
     * offered yet.
     */
    private ProficienciesGained applyMulticlassProficiencies(PlayerCharacter character, CharacterClass newClass) {
        String json = newClass.getMulticlassProficiencies();
        if (json == null || json.isBlank()) return null;
        JsonNode grants;
        try {
            grants = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ConflictException("The multiclassing proficiencies of the class " + newClass.getName()
                    + " can't be read");
        }
        List<String> armor = CharacterUpdates.added(character.getArmorProficiencies(), texts(grants.path("armor")));
        List<String> weapons = CharacterUpdates.added(character.getWeaponProficiencies(), texts(grants.path("weapons")));
        List<String> tools = CharacterUpdates.added(character.getToolProficiencies(), texts(grants.path("tools")));
        if (!armor.isEmpty()) {
            character.setArmorProficiencies(CharacterUpdates.merged(character.getArmorProficiencies(), armor));
        }
        if (!weapons.isEmpty()) {
            character.setWeaponProficiencies(CharacterUpdates.merged(character.getWeaponProficiencies(), weapons));
        }
        if (!tools.isEmpty()) {
            character.setToolProficiencies(CharacterUpdates.merged(character.getToolProficiencies(), tools));
        }
        return new ProficienciesGained(armor, weapons, tools);
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual() && !item.asText().isBlank()) texts.add(item.asText());
        }
        return texts;
    }

    // Primal Champion at Barbarian 20th level: Strength and Constitution up by 4, to the new maximum of 24, recorded
    // at the amount applied so that levelling down takes off exactly that
    private static List<AbilityBonus> applyPrimalChampion(PlayerCharacter character, CharacterClass levelClass,
                                                          int classLevel) {
        if (!BARBARIAN.equals(levelClass.getName()) || classLevel != PRIMAL_CHAMPION_LEVEL) {
            return List.of();
        }
        return List.of(
                new AbilityBonus(Ability.STR.fullName(),
                        CharacterUpdates.increaseAbility(character, Ability.STR.fullName(), PRIMAL_CHAMPION_INCREASE)),
                new AbilityBonus(Ability.CON.fullName(),
                        CharacterUpdates.increaseAbility(character, Ability.CON.fullName(), PRIMAL_CHAMPION_INCREASE)));
    }

    // ── Levelling down ───────────────────────────────────────────

    /**
     * Removes the last level: its history entry goes, only what it recorded is reversed (its features, choices,
     * proficiencies and ability increases), and the Derived Stats and pools are worked out again, capping current hit
     * points and spent Hit Dice (D167).
     */
    @Transactional
    public CharacterResponse levelDown(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);
        if (character.getLevel() <= 1)
            throw new ConflictException("Character is already at minimum level");

        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        if (history.isEmpty())
            throw new ConflictException("No level history to revert");
        LevelHistoryEntry lastEntry = history.removeLast();

        CharacterUpdates.removeFeatures(character, orEmpty(lastEntry.featuresGained()));
        reverseChoices(character, lastEntry.choices());
        CharacterUpdates.removeProficiencies(character, lastEntry.proficienciesGained());
        for (AbilityBonus increase : orEmpty(lastEntry.abilityIncreases())) {
            CharacterUpdates.decreaseAbility(character, increase.ability(), increase.bonus());
        }

        UUID classId = lastEntry.classId();
        if (classId != null) {
            Map<UUID, Integer> classLevels = new LinkedHashMap<>(
                    MulticlassValidator.classLevels(character.getMulticlassEntries()));
            int newClassLevel = classLevels.getOrDefault(classId, 1) - 1;
            if (newClassLevel <= 0) {
                classLevels.remove(classId);
            } else {
                classLevels.put(classId, newClassLevel);
            }
            CharacterUpdates.rebuildClassEntries(character, classLevels);
        }

        character.setLevelHistory(history);
        character.setLevel(character.getLevel() - 1);
        characterStats.apply(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    // Reverses the choices a level recorded: Expertise, and the Ability Score Improvement by the amount applied, or,
    // for one recorded before R6, the amount requested
    private void reverseChoices(PlayerCharacter character, LevelChoices choices) {
        if (choices == null) return;
        if (choices.expertise() != null && !choices.expertise().isEmpty()) {
            character.setSkillExpertises(CharacterUpdates.without(character.getSkillExpertises(), choices.expertise()));
        }
        AbilityScoreImprovement asi = choices.asi();
        if (asi == null) return;
        if ("ability".equals(asi.type())) {
            for (AbilityBonus increase : asi.applied() != null ? asi.applied() : orEmpty(asi.increases())) {
                CharacterUpdates.decreaseAbility(character, increase.ability(), increase.bonus());
            }
        } else if ("feat".equals(asi.type())) {
            if (asi.appliedEffects() != null) {
                featEffectResolver.reverseFeatEffects(character, asi.appliedEffects());
            } else {
                if (asi.featName() != null) CharacterUpdates.removeFeatureByName(character, asi.featName());
                if (asi.applied() != null) {
                    for (AbilityBonus increase : asi.applied()) {
                        CharacterUpdates.decreaseAbility(character, increase.ability(), increase.bonus());
                    }
                } else if (asi.featAbility() != null && !asi.featAbility().isBlank()) {
                    CharacterUpdates.decreaseAbility(character, asi.featAbility(), 1);
                }
            }
        }
    }

    // ── Choices ──────────────────────────────────────────────────

    @Transactional
    public CharacterResponse applyChoices(UUID characterId, ApplyChoicesRequest request, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        if (request.getAsi() != null) {
            applyAsi(character, request.getAsi());
        }

        if (request.getExpertiseSkills() != null && !request.getExpertiseSkills().isEmpty()) {
            applyExpertise(character, request.getExpertiseSkills());
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

        characterStats.apply(character);
        character = characterRepository.save(character);
        return characterMapper.toResponse(character);
    }

    /**
     * Applies an Ability Score Improvement, or a feat in its place, to the latest level that owes one, and records it
     * there (D167): +2 to one score or +1 to two, within each score's maximum, recorded at the amount applied.
     */
    private void applyAsi(PlayerCharacter character, ApplyChoicesRequest.AsiChoice asi) {
        int index = CharacterUpdates.owedAsiLevel(orEmpty(character.getLevelHistory()));
        if (index < 0)
            throw new ConflictException("No Ability Score Improvement is owed");

        if ("ability".equals(asi.getType())) {
            List<AbilityBonus> requested = validIncreases(character, asi.getIncreases());
            List<AbilityBonus> applied = requested.stream()
                    .map(increase -> new AbilityBonus(increase.ability(),
                            CharacterUpdates.increaseAbility(character, increase.ability(), increase.bonus())))
                    .toList();
            CharacterUpdates.recordAsi(character, index,
                    new AbilityScoreImprovement("ability", requested, null, null, null, null, applied));
        } else if ("feat".equals(asi.getType())) {
            if (asi.getFeatId() != null) {
                Feat feat = featRepository.findById(asi.getFeatId())
                        .orElseThrow(() -> new ValidationException("Feat not found"));
                FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, asi);
                CharacterUpdates.recordAsi(character, index, new AbilityScoreImprovement("feat", null, feat.getName(),
                        asi.getFeatId(), null, CharacterUpdates.recordedEffects(applied, feat.getName()), null));
            } else if (asi.getFeatName() != null && !asi.getFeatName().isBlank()) {
                List<Feature> features = copy(character.getFeatures());
                features.add(new Feature(asi.getFeatName(), "Feat", "Feat"));
                character.setFeatures(features);
                List<AbilityBonus> applied = null;
                if (asi.getFeatAbility() != null && !asi.getFeatAbility().isBlank()) {
                    applied = List.of(new AbilityBonus(asi.getFeatAbility(),
                            CharacterUpdates.increaseAbility(character, asi.getFeatAbility(), 1)));
                }
                CharacterUpdates.recordAsi(character, index, new AbilityScoreImprovement("feat", null,
                        asi.getFeatName(), null, asi.getFeatAbility(), null, applied));
            } else {
                throw new ValidationException("A feat taken in place of an Ability Score Improvement needs its ID or name");
            }
        } else {
            throw new ValidationException("An Ability Score Improvement is of type 'ability' or 'feat'");
        }
    }

    /**
     * The increases of an Ability Score Improvement, each spelled out: +2 to one score or +1 to two different scores
     * (PHB p.15), none above its maximum.
     *
     * @throws ValidationException for anything else
     */
    private List<AbilityBonus> validIncreases(PlayerCharacter character,
                                              List<ApplyChoicesRequest.AbilityIncrease> increases) {
        String rule = "An Ability Score Improvement is +2 to one ability score or +1 to two";
        if (increases == null || increases.isEmpty() || increases.size() > 2) {
            throw new ValidationException(rule);
        }
        AbilityScoreMaximums maximums = characterStats.abilityScoreMaximums(character);
        Set<Ability> abilities = EnumSet.noneOf(Ability.class);
        List<AbilityBonus> valid = new ArrayList<>();
        for (ApplyChoicesRequest.AbilityIncrease increase : increases) {
            Ability ability = CharacterUpdates.ability(increase.getAbility());
            int expected = increases.size() == 1 ? 2 : 1;
            if (increase.getBonus() != expected || !abilities.add(ability)) {
                throw new ValidationException(rule);
            }
            int score = CharacterUpdates.score(character, ability);
            int maximum = maximum(maximums, ability);
            if (score + increase.getBonus() > maximum) {
                throw new ValidationException(capitalised(ability.fullName()) + " is " + score
                        + ", and an Ability Score Improvement can't take it above " + maximum);
            }
            valid.add(new AbilityBonus(ability.fullName(), increase.getBonus()));
        }
        return valid;
    }

    private static int maximum(AbilityScoreMaximums maximums, Ability ability) {
        return switch (ability) {
            case STR -> maximums.strength();
            case DEX -> maximums.dexterity();
            case CON -> maximums.constitution();
            case INT -> maximums.intelligence();
            case WIS -> maximums.wisdom();
            case CHA -> maximums.charisma();
        };
    }

    private static String capitalised(String text) {
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /**
     * Gives skills Expertise for the latest level that owes it, recording on that level those it didn't have so that
     * levelling down removes them (D167).
     */
    private void applyExpertise(PlayerCharacter character, List<String> skills) {
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        int index = CharacterUpdates.owedExpertiseLevel(history);
        if (index < 0)
            throw new ConflictException("No Expertise is owed");
        LevelHistoryEntry level = history.get(index);
        int allowed = ClassProgression.expertiseChoices(level.className(), level.classLevel());
        List<String> chosen = skills.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (chosen.size() > allowed)
            throw new ValidationException("Level " + level.classLevel() + " " + level.className() + " gives Expertise in "
                    + allowed + " skills, not " + chosen.size());

        List<String> added = CharacterUpdates.added(character.getSkillExpertises(), chosen);
        character.setSkillExpertises(CharacterUpdates.merged(character.getSkillExpertises(), chosen));
        CharacterUpdates.recordExpertise(character, index, added);
    }

    @Transactional(readOnly = true)
    public List<EligibleClassResponse> getEligibleClasses(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        List<CharacterClass> allClasses = characterClassRepository.findAll();
        return MulticlassValidator.getEligibleClasses(character, allClasses);
    }

    // ── Derived Stats for other modules ──────────────────────────

    /**
     * The calculator entries for each of the character's casting classes, read from its class entries. Shared by the
     * recompute-spell-slots maintenance job (D153).
     */
    public Map<String, SpellSlotCalculator.ClassEntry> spellSlotEntries(PlayerCharacter character) {
        return characterStats.spellSlotEntries(character);
    }

    /** What the rules give the character now, beside what its sheet stores; changes nothing (D167). */
    public DerivedStats derivedStats(PlayerCharacter character) {
        return characterStats.derive(character);
    }

    /**
     * Works the character's Derived Stats out again, writes their stored copies and syncs its pools, for the
     * recompute-characters job; the caller saves it.
     */
    public DerivedStats recalculate(PlayerCharacter character) {
        return characterStats.apply(character);
    }

    /**
     * What the character casts with in one of its classes, worked out from its live sheet, which class spells in
     * combat use (D164); empty for a class it has no spells in.
     */
    public Optional<ClassSpellcasting> classSpellcasting(PlayerCharacter character, String className) {
        return characterStats.spellcasting(character).stream()
                .filter(c -> c.className().equals(className))
                .findFirst();
    }

    private int getCurrentClassLevel(PlayerCharacter character, UUID classId) {
        return MulticlassValidator.classLevels(character.getMulticlassEntries()).getOrDefault(classId, 1);
    }

    // ── Rests ────────────────────────────────────────────────────

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
     * Resets resource pools matching the given trigger (shortRest / longRest), innate uses included on a long rest.
     * A pool whose reset can't be worked out stops the rest (D155).
     */
    private void resetResourcePools(PlayerCharacter character, String resetTrigger) {
        List<ResourcePoolEntry> pools = orEmpty(character.getResourcePools());
        if (pools.isEmpty()) return;
        Map<String, Integer> context = resourcePoolService.buildCharacterContext(character);
        character.setResourcePools(new ArrayList<>(resourcePoolService.resetPools(pools, resetTrigger, context).pools()));
    }

    // ── Deleting and reading ─────────────────────────────────────

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

    static int abilityMod(Integer score) {
        if (score == null) return 0;
        return Ability.modifier(score);
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
}
