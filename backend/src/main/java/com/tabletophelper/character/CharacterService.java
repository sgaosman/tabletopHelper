package com.tabletophelper.character;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.campaign.AccessPolicy;
import com.tabletophelper.campaign.Campaign;
import com.tabletophelper.campaign.CampaignRepository;
import com.tabletophelper.character.dto.*;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.Currency;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.identity.User;
import com.tabletophelper.identity.UserService;
import com.tabletophelper.reference.*;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.ProficiencyBonus;
import com.tabletophelper.rules.character.ClassTables;
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
 * Characters: creating them, levelling them up and down, deleting them and reading them. The choices levels bring are
 * PendingChoiceService's (D159): creating and levelling add them, and levelling down undoes the last level's. The
 * sheet's other commands are CharacterSheetService's, RestService's and CharacterSpellService's, and the DM's
 * adjustments DmAdjustmentService's (D158). Every change starts in CharacterCommands, which checks access and locks the
 * row, and after it CharacterStats works out the Derived Stats again, writes their stored copies and syncs the pools
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
    private final CharacterMapper characterMapper;
    private final ObjectMapper objectMapper;
    private final AccessPolicy accessPolicy;
    private final CharacterStats characterStats;
    private final CharacterCommands characterCommands;
    private final LiveEncounters liveEncounters;
    private final InventoryChecks inventoryChecks;
    private final PendingChoiceService pendingChoices;
    private final ClassTables classTables;

    // ── Creating ─────────────────────────────────────────────────

    @Transactional
    public CharacterResponse createCharacter(CharacterCreateRequest request, UUID userId) {
        PlayerCharacter character = characterRepository.save(newCharacter(request, userId));
        return characterMapper.toResponse(character);
    }

    /**
     * How the armour class of the character a create request describes would add up, its starting equipment's armour
     * and shield included, worked out as creation works it out and saved nowhere: the creation wizard's preview.
     */
    @Transactional(readOnly = true)
    public ArmourClassBreakdown previewArmourClass(CharacterCreateRequest request, UUID userId) {
        return characterStats.armourClassBreakdown(newCharacter(request, userId));
    }

    // The character a create request describes, its Pending Choices added and its Derived Stats worked out, unsaved
    private PlayerCharacter newCharacter(CharacterCreateRequest request, UUID userId) {
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
                    mcSubclassRef = subclassRepository.findById(mcSubclassId)
                            .orElseThrow(() -> new ValidationException("Subclass not found: " + mcSubclassId));
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

            progression = LevelUpCalculator.buildMulticlassProgression(objectMapper, classTables, classInputs);
            multiclassEntries = mcEntryList;
            characterClassName = classInputs.stream()
                    .map(LevelUpCalculator.ClassInput::className)
                    .reduce((a, b) -> a + " / " + b).orElse(className);
        } else {
            progression = LevelUpCalculator.buildProgression(objectMapper, classTables,
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

        List<Feature> features = LevelUpCalculator.classFeatures(progression);

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
                .savingThrowProficiencies(request.getSavingThrowProficiencies())
                .skillProficiencies(request.getSkillProficiencies())
                .skillExpertises(request.getSkillExpertises())
                .armorProficiencies(request.getArmorProficiencies())
                .weaponProficiencies(request.getWeaponProficiencies())
                .toolProficiencies(request.getToolProficiencies())
                .languageProficiencies(request.getLanguageProficiencies())
                .features(features)
                .damageResistances(request.getDamageResistances())
                .spellsKnown(request.getSpellsKnown())
                .spellcastingAbility(chosenSpellcastingAbility(request.getSpellcastingAbility()))
                .equipment(request.getEquipment() != null ? inventoryChecks.checked(request.getEquipment()) : null)
                .currency(request.getCurrency() != null ? InventoryChecks.checked(request.getCurrency()) : Currency.NONE)
                .multiclassEntries(multiclassEntries)
                .levelHistory(LevelUpCalculator.levelHistory(progression))
                .build();

        recordCreationHpGains(character);
        pendingChoices.owedAtCreation(character, request);
        // The race's choice, or else the first casting class's, so a feat taken later that chooses its own ability
        // doesn't replace the class's (D159)
        if (character.getSpellcastingAbility() == null) {
            pendingChoices.storeClassSpellcastingAbility(character);
        }
        characterStats.apply(character);
        return character;
    }

    // The ability the race lets the player choose for its spells, abbreviated; null for none
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

    // ── Levelling up ─────────────────────────────────────────────

    @Transactional
    public LevelUpResponse levelUp(UUID characterId, LevelUpRequest request, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId, "level up");
        CharacterCommands.requireAlive(character, "level up");
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
            List<String> unmet = MulticlassValidator.unmetPrerequisites(objectMapper, character, levelClass,
                    currentClasses(character));
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
        List<LevelUpCalculator.FeatureEntry> newFeatures = LevelUpCalculator.collectFeaturesForLevel(objectMapper,
                levelClass.getFeatures(), subclassFeatures ? levelSubclass.getFeatures() : null, newClassLevel,
                levelClass.getName(), subclassFeatures ? levelSubclass.getName() : null);

        character.setLevel(newCharLevel);
        CharacterUpdates.appendFeatures(character, newFeatures);
        CharacterUpdates.updateClassEntry(character, levelClassId, levelClass, levelSubclass, classLevels);
        ProficienciesGained proficienciesGained = isNewClass ? applyMulticlassProficiencies(character, levelClass) : null;
        List<AbilityBonus> abilityIncreases = applyPrimalChampion(character, levelClass, newClassLevel);
        CharacterUpdates.appendLevelHistory(character, newCharLevel, levelClassId, levelClass.getName(), newClassLevel,
                HitPoints.fixedHitDieResult(newCharLevel, levelClass.getHitDice()), newFeatures, proficienciesGained,
                abilityIncreases);
        pendingChoices.owedAtLevelUp(character, levelClass, newCharLevel, newClassLevel);
        // Levelling into a first casting class stores its ability, marked on its level, so levelling down past that
        // level clears it again and a feat taken later doesn't replace it (D159)
        if (character.getSpellcastingAbility() == null || character.getSpellcastingAbility().isBlank()) {
            pendingChoices.storeClassSpellcastingAbility(character);
        }

        int oldHpMax = character.getHpMax();
        DerivedStats stats = characterStats.apply(character);
        CharacterUpdates.recordHpGained(character, stats.hpMax() - oldHpMax);
        character = characterRepository.save(character);

        return LevelUpResponse.builder()
                .character(characterMapper.toResponse(character))
                .newFeatures(newFeatures.stream().map(LevelUpCalculator.FeatureEntry::name).toList())
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
        } catch (JacksonException e) {
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
            if (item.isString() && !item.asString("").isBlank()) texts.add(item.asString(""));
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
     * proficiencies and ability increases), the choices it still owed go (D159), and the Derived Stats and pools are
     * worked out again, capping current hit points and spent Hit Dice (D167).
     */
    @Transactional
    public CharacterResponse levelDown(UUID characterId, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId, "level down");
        CharacterCommands.requireAlive(character, "level down");
        if (character.getLevel() <= 1)
            throw new ConflictException("Character is already at minimum level");

        List<LevelHistoryEntry> history = copy(character.getLevelHistory());
        if (history.isEmpty())
            throw new ConflictException("No level history to revert");
        LevelHistoryEntry lastEntry = history.removeLast();
        // Before reversing, so that a spellcasting ability the level's feat set passes to a feat still applied (D159)
        character.setLevelHistory(history);

        CharacterUpdates.removeFeatures(character, orEmpty(lastEntry.featuresGained()));
        pendingChoices.levelRemoved(character, lastEntry);
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
        if (lastEntry.choices() != null && Boolean.TRUE.equals(lastEntry.choices().setsSpellcastingAbility())) {
            pendingChoices.classSpellcastingAbilityRemoved(character);
        }

        character.setLevel(character.getLevel() - 1);
        return characterCommands.finish(character);
    }

    @Transactional(readOnly = true)
    public List<EligibleClassResponse> getEligibleClasses(UUID characterId, UUID userId) {
        PlayerCharacter character = loadCharacter(characterId);
        accessPolicy.requireCharacterOwner(character.getUser().getId(), userId);

        List<CharacterClass> allClasses = characterClassRepository.findAll();
        return MulticlassValidator.getEligibleClasses(objectMapper, character, allClasses);
    }

    // ── Derived Stats for other modules ──────────────────────────

    /**
     * The spellcasting ability a character with none stored would store from its first casting class (D159), as
     * creation and levelling up store it, with the level it would be recorded on; changes nothing. Empty when the
     * character stores an ability already or none of its classes casts.
     */
    public Optional<MissingSpellcastingAbility> missingSpellcastingAbility(PlayerCharacter character) {
        if (character.getSpellcastingAbility() != null && !character.getSpellcastingAbility().isBlank()) {
            return Optional.empty();
        }
        PendingChoiceService.ClassSpellcastingAbility found = pendingChoices.classSpellcastingAbility(character);
        return Optional.ofNullable(found).map(f -> new MissingSpellcastingAbility(f.ability(), f.className(),
                f.classLevel(), f.levelIndex() >= 0));
    }

    /**
     * Stores the ability {@link #missingSpellcastingAbility} finds, recorded on its level when the history has it,
     * for the recompute-characters job; the caller saves the character.
     *
     * @return whether it stored one
     */
    public boolean storeMissingSpellcastingAbility(PlayerCharacter character) {
        if (missingSpellcastingAbility(character).isEmpty()) return false;
        return pendingChoices.storeClassSpellcastingAbility(character);
    }

    /**
     * A spellcasting ability a character would store from a class.
     *
     * @param className  the first of its classes that casts
     * @param classLevel the class level that gives the class its spellcasting
     * @param recorded   whether its level history has that level, so levelling down past it clears the ability
     */
    public record MissingSpellcastingAbility(String ability, String className, int classLevel, boolean recorded) {}

    /**
     * The bonus proficiencies the character's subclasses grant and its sheet lacks, such as a Life Domain cleric's heavy
     * armour (PHB p.60) for a subclass chosen before subclasses granted them, for the recompute-characters job; changes
     * nothing.
     *
     * @throws ConflictException when a subclass the character has can't be found or read
     */
    public List<PendingChoiceService.MissingSubclassProficiencies> missingSubclassProficiencies(
            PlayerCharacter character) {
        return pendingChoices.missingSubclassProficiencies(character);
    }

    /**
     * Gives the character what {@link #missingSubclassProficiencies} finds, each recorded on the level that records its
     * subclass when the history has one, for the recompute-characters job; the caller saves the character.
     */
    public void storeMissingSubclassProficiencies(PlayerCharacter character) {
        pendingChoices.storeMissingSubclassProficiencies(character);
    }

    /**
     * The class and subclass features on the character's sheet whose text the class data now gives differently, for
     * the recompute-characters job; changes nothing. A sheet copies a feature's text when its level is gained, so a
     * sheet made before the class loader's version 4 lacks the text of the features nested inside another, such as the
     * Life Domain's Bonus Proficiency and Disciple of Life inside "Life Domain" (PHB p.60). A feature is matched by its
     * name and source, the class or subclass that gave it, among the features of that class's levels the character
     * has; a name the data gives several different texts is left alone, as is any other feature.
     *
     * @throws ConflictException when a class or subclass the character has can't be found or read
     */
    public List<OutdatedFeatureText> outdatedFeatureTexts(PlayerCharacter character) {
        Map<String, Map<String, String>> texts = featureTexts(character);
        List<OutdatedFeatureText> outdated = new ArrayList<>();
        for (Feature feature : orEmpty(character.getFeatures())) {
            if (feature == null || feature.name() == null || feature.source() == null) continue;
            String text = texts.getOrDefault(feature.source(), Map.of()).get(feature.name());
            if (text != null && !text.equals(feature.description())) {
                outdated.add(new OutdatedFeatureText(feature.name(), feature.source(),
                        feature.description() != null ? feature.description().length() : 0, text.length()));
            }
        }
        return outdated;
    }

    /**
     * Gives the features {@link #outdatedFeatureTexts} finds the class data's text, keeping their names, sources and
     * places, so levelling down still finds them; the caller saves the character.
     */
    public void storeFeatureTexts(PlayerCharacter character) {
        Map<String, Map<String, String>> texts = featureTexts(character);
        List<Feature> features = new ArrayList<>();
        for (Feature feature : orEmpty(character.getFeatures())) {
            String text = feature == null || feature.name() == null || feature.source() == null ? null
                    : texts.getOrDefault(feature.source(), Map.of()).get(feature.name());
            features.add(text != null ? new Feature(feature.name(), text, feature.source()) : feature);
        }
        character.setFeatures(features);
    }

    /**
     * A sheet feature whose text the class data now gives differently.
     *
     * @param name       the feature's name
     * @param source     the class or subclass that gave it
     * @param lengthNow  its text's length on the sheet
     * @param lengthData the data's text's length
     */
    public record OutdatedFeatureText(String name, String source, int lengthNow, int lengthData) {}

    // Each class's and subclass's feature texts by source and name, from the levels of it the character has, leaving out
    // a name the data gives more than one text
    private Map<String, Map<String, String>> featureTexts(PlayerCharacter character) {
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        if (entries.isEmpty() && character.getClassRef() != null) {
            CharacterClass only = character.getClassRef();
            Subclass subclass = character.getSubclassRef();
            entries = List.of(new ClassEntry(only.getId(), only.getName(), subclass != null ? subclass.getId() : null,
                    subclass != null ? subclass.getName() : null,
                    character.getLevel() != null ? character.getLevel() : 0));
        }
        Map<String, Map<String, Set<String>>> found = new HashMap<>();
        for (ClassEntry entry : entries) {
            if (entry == null || entry.classId() == null) continue;
            CharacterClass characterClass = characterClassRepository.findById(entry.classId())
                    .orElseThrow(() -> new ConflictException("The class " + entry.className() + " of "
                            + character.getName() + " can't be found"));
            addFeatureTexts(found, characterClass.getName(), characterClass.getFeatures(), entry.level(),
                    "class " + characterClass.getName());
            if (entry.subclassId() != null) {
                Subclass subclass = subclassRepository.findById(entry.subclassId())
                        .orElseThrow(() -> new ConflictException("The subclass " + entry.subclassName() + " of "
                                + character.getName() + "'s " + entry.className() + " can't be found"));
                addFeatureTexts(found, subclass.getName(), subclass.getFeatures(), entry.level(),
                        "subclass " + subclass.getName());
            }
        }
        Map<String, Map<String, String>> texts = new HashMap<>();
        found.forEach((source, byName) -> byName.forEach((name, descriptions) -> {
            if (descriptions.size() == 1) {
                texts.computeIfAbsent(source, s -> new HashMap<>()).put(name, descriptions.iterator().next());
            }
        }));
        return texts;
    }

    private void addFeatureTexts(Map<String, Map<String, Set<String>>> found, String source, String featureJson,
                                 int classLevel, String what) {
        if (featureJson == null || source == null) return;
        JsonNode features;
        try {
            features = objectMapper.readTree(featureJson);
        } catch (JacksonException e) {
            throw new ConflictException("The features of the " + what + " can't be read");
        }
        if (!features.isArray()) {
            throw new ConflictException("The features of the " + what + " can't be read");
        }
        for (JsonNode feature : features) {
            String name = feature.path("name").asString(null);
            if (name == null || feature.path("level").asInt(0) > classLevel) continue;
            found.computeIfAbsent(source, s -> new HashMap<>()).computeIfAbsent(name, n -> new HashSet<>())
                    .add(feature.path("description").asString(""));
        }
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

    /**
     * The spellcasting numbers of the class whose subclass this is, for a cantrip the subclass taught, such as a Nature
     * Domain cleric's druid cantrip, cast with Wisdom as the cleric's (PHB p.61); empty when that class doesn't cast,
     * as an Arcane Archer fighter's doesn't.
     */
    public Optional<ClassSpellcasting> subclassSpellcasting(PlayerCharacter character, String subclassName) {
        return characterStats.spellcasting(character).stream()
                .filter(c -> c.subclassName() != null && c.subclassName().equals(subclassName))
                .findFirst();
    }

    /**
     * The ability the character's race and feat spells are cast with: the one chosen for them, or else its first
     * casting class's (D164); null for a character without one.
     */
    public String mainSpellcastingAbility(PlayerCharacter character) {
        return characterStats.mainSpellcastingAbility(character);
    }

    // ── Deleting and reading ─────────────────────────────────────

    /**
     * Soft-deletes a character. One in an encounter that hasn't been completed, a Preparing one included, can't be
     * deleted.
     */
    @Transactional
    public void deleteCharacter(UUID characterId, UUID userId) {
        PlayerCharacter character = characterCommands.beginAsOwner(characterId, userId);
        if (liveEncounters.includesUnfinished(characterId)) {
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
