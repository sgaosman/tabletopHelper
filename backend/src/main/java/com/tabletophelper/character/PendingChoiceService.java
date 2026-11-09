package com.tabletophelper.character;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.AbilityScoreMaximums;
import com.tabletophelper.character.dto.ApplyChoicesRequest;
import com.tabletophelper.character.dto.CharacterCreateRequest;
import com.tabletophelper.character.dto.CharacterResponse;
import com.tabletophelper.character.internal.CharacterStats;
import com.tabletophelper.character.state.AbilityBonus;
import com.tabletophelper.character.state.AbilityScoreImprovement;
import com.tabletophelper.character.state.AppliedFeatEffects;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.character.state.FeatTaken;
import com.tabletophelper.character.state.Feature;
import com.tabletophelper.character.state.KnownSpell;
import com.tabletophelper.character.state.LevelChoices;
import com.tabletophelper.character.state.LevelHistoryEntry;
import com.tabletophelper.character.state.PendingChoice;
import com.tabletophelper.character.state.PendingChoice.Source;
import com.tabletophelper.character.state.PendingChoice.Type;
import com.tabletophelper.character.state.ProficienciesGained;
import com.tabletophelper.character.state.SpellsLearned;
import com.tabletophelper.character.state.SubclassChosen;
import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.CharacterClassRepository;
import com.tabletophelper.reference.Feat;
import com.tabletophelper.reference.FeatRepository;
import com.tabletophelper.reference.OptionalFeature;
import com.tabletophelper.reference.OptionalFeatureRepository;
import com.tabletophelper.reference.Race;
import com.tabletophelper.reference.Subclass;
import com.tabletophelper.reference.SubclassRepository;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.ClassProgression;
import com.tabletophelper.rules.character.ClassTables;
import com.tabletophelper.rules.combat.Metamagic;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static com.tabletophelper.character.CharacterUpdates.copy;
import static com.tabletophelper.character.CharacterUpdates.orEmpty;

/**
 * Pending Choices (D159): the choices a character's levels owe that its player hasn't made yet, which the sheet keeps
 * (V23) until they are made. Levelling up adds the new level's. Creating a character at level N adds those of levels
 * 1 to N, less what the creation request chose itself (a subclass, Expertise, and the cantrips and spells of the
 * levels it gave them for, oldest first), and a FEAT choice for each feat its race or background gives. Levelling down
 * removes the last level's own and reverses those made for it.
 * <p>
 * Making one is apply-choices: the request names one choice by its ID, a 409 when the character has none such, and
 * gives the answer its type takes, a 400 when the answer doesn't fit. The answer is recorded on the history entry of
 * the level that owes the choice, so levelling down past that level reverses exactly it, and the choice leaves the
 * list. A subclass also brings the features and choices it gives the class levels the character already has. Like
 * levelling, it waits while the character is in an Active or Paused encounter (D166). A player may leave a choice for
 * later, and level up or join an encounter meanwhile; the DM sees how many are pending but can't make or waive one.
 */
@Service
@RequiredArgsConstructor
public class PendingChoiceService {

    // A class with no subclass level never asks for one
    private static final int NO_SUBCLASS_LEVEL = 99;
    private static final String FIGHTING_STYLE_FEATURES = "FightingStyle";
    private static final String FIGHTING_STYLE = "Fighting Style: ";
    private static final String METAMAGIC_FEATURES = "Metamagic";

    private final CharacterCommands characterCommands;
    private final CharacterStats characterStats;
    private final CharacterSpellService spellService;
    private final FeatEffectResolver featEffectResolver;
    private final FeatEligibility featEligibility;
    private final CharacterClassRepository classRepository;
    private final SubclassRepository subclassRepository;
    private final FeatRepository featRepository;
    private final OptionalFeatureRepository optionalFeatureRepository;
    private final ObjectMapper objectMapper;
    private final ClassTables classTables;

    // ── Making a choice ──────────────────────────────────────────

    /**
     * Makes one of the character's Pending Choices with the answer the request gives, records it on the level that
     * owes it, and takes the choice off the list.
     *
     * @throws ConflictException   for a choice the character doesn't have, or while it is in an Active or Paused
     *                             encounter
     * @throws ValidationException for an answer that doesn't fit the choice, or a feat whose prerequisite the character
     *                             doesn't meet, naming what it misses (D159); a DM's grant isn't refused so
     */
    @Transactional
    public CharacterResponse apply(UUID characterId, ApplyChoicesRequest request, UUID userId) {
        PlayerCharacter character = characterCommands.beginOutsideEncounters(characterId, userId,
                "make the choices its levels bring");
        PendingChoice choice = orEmpty(character.getPendingChoices()).stream()
                .filter(c -> c.id().equals(request.getChoiceId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(character.getName() + " has no pending choice "
                        + request.getChoiceId()));
        int index = CharacterUpdates.historyIndex(character, choice.characterLevel());
        if (index < 0) {
            throw new ConflictException("Level " + choice.characterLevel() + ", which owes this choice, isn't in "
                    + character.getName() + "'s level history");
        }
        refuseOtherAnswers(request, choice.type());

        List<PendingChoice> brought = List.of();
        switch (choice.type()) {
            case ASI -> applyAsi(character, index, request.getAsi());
            case FEAT -> applyFeat(character, choice, index, request.getFeat());
            case SUBCLASS -> brought = applySubclass(character, choice, index, request.getSubclassId());
            case EXPERTISE -> applyExpertise(character, choice, index, request.getExpertiseSkills());
            case FIGHTING_STYLE -> applyFightingStyle(character, choice, index, request.getFightingStyle());
            case METAMAGIC -> applyMetamagic(character, choice, index, request.getMetamagic());
            case PROFICIENCY -> applyProficiencies(character, choice, index, request.getProficiencies());
            case CANTRIPS -> {
                List<String> learned = spellService.learnCantrips(character, choice, request.getSpells());
                CharacterUpdates.recordChoices(character, index, c -> c.withCantrips(
                        CharacterUpdates.merged(c.cantrips(), learned)));
            }
            case SPELLS -> {
                SpellsLearned learned = spellService.learnSpells(character, choice, request.getSpells(),
                        request.getSwap());
                CharacterUpdates.recordChoices(character, index, c -> c.withSpells(learned));
            }
        }

        List<PendingChoice> pending = copy(character.getPendingChoices());
        pending.removeIf(c -> c.id().equals(choice.id()));
        pending.addAll(brought);
        pending.sort(PendingChoices.ORDER);
        character.setPendingChoices(pending);
        return characterCommands.finish(character);
    }

    // An answer gives the fields its type takes and nothing else, so the level records only what the choice means
    private static void refuseOtherAnswers(ApplyChoicesRequest request, Type type) {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("asi", request.getAsi());
        given.put("feat", request.getFeat());
        given.put("subclassId", request.getSubclassId());
        given.put("expertiseSkills", request.getExpertiseSkills());
        given.put("fightingStyle", request.getFightingStyle());
        given.put("metamagic", request.getMetamagic());
        given.put("proficiencies", request.getProficiencies());
        given.put("spells", request.getSpells());
        given.put("swap", request.getSwap());
        List<String> takes = switch (type) {
            case ASI -> List.of("asi");
            case FEAT -> List.of("feat");
            case SUBCLASS -> List.of("subclassId");
            case EXPERTISE -> List.of("expertiseSkills");
            case FIGHTING_STYLE -> List.of("fightingStyle");
            case METAMAGIC -> List.of("metamagic");
            case PROFICIENCY -> List.of("proficiencies");
            case CANTRIPS -> List.of("spells");
            case SPELLS -> List.of("spells", "swap");
        };
        given.forEach((field, value) -> {
            if (value != null && !takes.contains(field)) {
                throw new ValidationException("This " + type + " choice takes " + String.join(" and ", takes)
                        + ", not " + field);
            }
        });
    }

    // ── Ability Score Improvements and feats ─────────────────────

    /**
     * An Ability Score Improvement, or a feat in its place, recorded on its level (D167): +2 to one score or +1 to
     * two, within each score's maximum, recorded at the amount applied. A feat the character already has can't be
     * taken again unless its text says it can (D158).
     */
    private void applyAsi(PlayerCharacter character, int index, ApplyChoicesRequest.AsiChoice asi) {
        if (asi == null) {
            throw new ValidationException("An Ability Score Improvement needs its increases, or a feat in its place");
        }
        LevelChoices recorded = character.getLevelHistory().get(index).choices();
        if (recorded != null && recorded.asi() != null) {
            throw new ConflictException("Level " + character.getLevelHistory().get(index).characterLevel()
                    + " already records its Ability Score Improvement");
        }
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
                requireNewFeat(character, feat);
                featEligibility.requireMet(character, feat);
                FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, asi);
                CharacterUpdates.recordAsi(character, index, new AbilityScoreImprovement("feat", null, feat.getName(),
                        asi.getFeatId(), null, CharacterUpdates.recordedEffects(applied, feat.getName()), null));
            } else if (asi.getFeatName() != null && !asi.getFeatName().isBlank()) {
                if (DmAdjustments.hasFeat(character, asi.getFeatName())) {
                    throw new ValidationException(character.getName() + " already has " + asi.getFeatName().trim());
                }
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
     * A feat a race or background gives at creation, such as a Variant Human's: one of the feats the choice offers, or
     * any, applied through FeatEffectResolver and recorded on the 1st level, so its effects count as an Ability Score
     * Improvement's feat's do, its limited uses' pool among them (D158).
     */
    private void applyFeat(PlayerCharacter character, PendingChoice choice, int index,
                           ApplyChoicesRequest.AsiChoice answer) {
        if (answer == null || answer.getFeatId() == null) {
            throw new ValidationException("A feat choice needs the feat's ID");
        }
        if (answer.getType() != null && !"feat".equals(answer.getType())) {
            throw new ValidationException("A feat choice is answered with a feat, not an Ability Score Improvement");
        }
        Feat feat = featRepository.findById(answer.getFeatId())
                .orElseThrow(() -> new ValidationException("Feat not found"));
        if (choice.options() != null && !containsIgnoringCase(choice.options(), feat.getName())) {
            throw new ValidationException("Choose one of " + String.join(", ", choice.options()) + ", not "
                    + feat.getName());
        }
        requireNewFeat(character, feat);
        featEligibility.requireMet(character, feat);
        FeatEffectResolver.AppliedEffects applied = featEffectResolver.applyFeat(character, feat, answer);
        FeatTaken taken = new FeatTaken(feat.getName(), feat.getId(),
                CharacterUpdates.recordedEffects(applied, feat.getName()));
        CharacterUpdates.recordChoices(character, index, c -> {
            List<FeatTaken> feats = copy(c.feats());
            feats.add(taken);
            return c.withFeats(feats);
        });
    }

    // A feat the character already has can't be taken again, unless its text says it can (D158)
    private void requireNewFeat(PlayerCharacter character, Feat feat) {
        if (DmAdjustments.hasFeat(character, feat.getName()) && !featEffectResolver.repeatable(feat)) {
            throw new ValidationException(character.getName() + " already has " + feat.getName()
                    + ", which can't be taken again");
        }
    }

    // ── Subclasses ───────────────────────────────────────────────

    /**
     * A subclass for one of the character's classes, recorded on its subclass level, so levelling down past that
     * level takes it away. It brings its features at each class level the character already has, from the subclass
     * level on, each recorded on its own level, and the choices it gives those levels, such as an Eldritch Knight's
     * cantrips and spells, and the bonus proficiencies it lets the player choose, owed on the subclass's own level.
     *
     * @return the choices the subclass brings
     */
    private List<PendingChoice> applySubclass(PlayerCharacter character, PendingChoice choice, int index,
                                              UUID subclassId) {
        if (subclassId == null) {
            throw new ValidationException("A subclass choice needs the subclass's ID");
        }
        Subclass subclass = subclassRepository.findById(subclassId)
                .orElseThrow(() -> new ValidationException("Subclass not found"));
        UUID classId = choice.classId();
        if (subclass.getCharacterClass() == null || !subclass.getCharacterClass().getId().equals(classId)) {
            throw new ValidationException(subclass.getName() + " isn't a " + choice.className() + " subclass");
        }
        if (choice.options() != null && !containsIgnoringCase(choice.options(), subclass.getName())) {
            throw new ValidationException("Choose one of the " + choice.className() + " subclasses, not "
                    + subclass.getName());
        }
        if (CharacterUpdates.subclassIdOf(character, classId) != null) {
            throw new ConflictException(character.getName() + "'s " + choice.className() + " already has a subclass");
        }
        CharacterClass characterClass = characterClass(classId, choice.className());

        CharacterUpdates.setClassEntrySubclass(character, classId, subclass);
        if (character.getClassRef() != null && classId.equals(character.getClassRef().getId())) {
            character.setSubclassRef(subclass);
            character.setSubclass(subclass.getName());
        }
        CharacterUpdates.recordChoices(character, index, c -> c.withSubclass(
                new SubclassChosen(classId, subclass.getId(), subclass.getName())));
        grantSubclassProficiencies(character, subclass, index);
        // An Eldritch Knight or Arcane Trickster stores its Intelligence when the character has no spellcasting
        // ability yet, so a feat taken later that chooses another doesn't replace it (D159)
        if (isBlank(character.getSpellcastingAbility())
                && ClassProgression.subclassCasterType(classTables, choice.className(), subclass.getName())
                .isPresent()) {
            storeClassSpellcastingAbility(character);
        }

        int subclassLevel = subclassLevel(characterClass);
        int classLevel = MulticlassValidator.classLevels(character.getMulticlassEntries()).getOrDefault(classId, 0);
        List<PendingChoice> brought = new ArrayList<>(proficiencyChoices(subclass, choice.characterLevel(), classId,
                choice.className(), choice.classLevel(), choice.source()));
        for (int level = Math.max(1, subclassLevel); level <= classLevel; level++) {
            int levelIndex = historyIndexOf(character, classId, choice.className(), level);
            if (levelIndex < 0) continue;
            List<LevelUpCalculator.FeatureEntry> features = LevelUpCalculator.collectFeaturesForLevel(objectMapper,
                    null, subclass.getFeatures(), level, choice.className(), subclass.getName());
            CharacterUpdates.appendFeatures(character, features);
            CharacterUpdates.appendFeaturesGained(character, levelIndex, features.stream()
                    .map(f -> new Feature(f.name(), f.description(), f.source()))
                    .toList());
            int characterLevel = character.getLevelHistory().get(levelIndex).characterLevel();
            brought.addAll(PendingChoices.owedBySubclass(classTables, characterLevel,
                    new PendingChoices.ClassLevel(classId, choice.className(), level, subclass.getName(),
                            subclassLevel, List.of()), choice.source()));
        }
        return brought;
    }

    // ── Expertise and Fighting Styles ────────────────────────────

    /**
     * Expertise in as many of the character's skill proficiencies as the choice counts, each one it doesn't have
     * Expertise in yet (PHB p.51, p.94), or in all of those left when there are fewer. Recorded on the level, so
     * levelling down takes it away.
     */
    private static void applyExpertise(PlayerCharacter character, PendingChoice choice, int index,
                                       List<String> skills) {
        List<String> proficient = orEmpty(character.getSkillProficiencies());
        List<String> expert = orEmpty(character.getSkillExpertises());
        long eligible = proficient.stream().filter(skill -> !containsIgnoringCase(expert, skill)).distinct().count();
        int needed = (int) Math.min(choice.count(), eligible);
        List<String> chosen = new ArrayList<>();
        for (String skill : distinct(orEmpty(skills))) {
            String proficiency = proficient.stream().filter(p -> p.trim().equalsIgnoreCase(skill)).findFirst()
                    .orElseThrow(() -> new ValidationException(character.getName() + " isn't proficient in " + skill
                            + ", so can't have Expertise in it"));
            if (containsIgnoringCase(expert, proficiency)) {
                throw new ValidationException(character.getName() + " already has Expertise in " + proficiency);
            }
            chosen.add(proficiency);
        }
        if (chosen.size() != needed) {
            throw new ValidationException("Choose " + needed + " skill" + (needed == 1 ? "" : "s")
                    + " for Expertise, not " + chosen.size());
        }
        character.setSkillExpertises(CharacterUpdates.merged(character.getSkillExpertises(), chosen));
        CharacterUpdates.recordExpertise(character, index, chosen);
    }

    /**
     * A Fighting Style from the class's list, one the character hasn't taken already (PHB p.72), as a feature of the
     * class that its level records, so levelling down takes it away.
     */
    private void applyFightingStyle(PlayerCharacter character, PendingChoice choice, int index, String style) {
        if (style == null || style.isBlank()) {
            throw new ValidationException("A Fighting Style choice needs the style");
        }
        List<String> options = choice.options() != null ? choice.options()
                : ClassProgression.fightingStyles(classTables, choice.className());
        String chosen = options.stream().filter(option -> option.equalsIgnoreCase(style.trim())).findFirst()
                .orElseThrow(() -> new ValidationException("A " + choice.className()
                        + " chooses its Fighting Style from " + String.join(", ", options) + ", not " + style.trim()));
        if (fightingStylesTaken(character).contains(chosen)) {
            throw new ValidationException(character.getName() + " already has the " + chosen + " Fighting Style");
        }
        Feature feature = new Feature(FIGHTING_STYLE + chosen, fightingStyleDescription(chosen), choice.className());
        List<Feature> features = copy(character.getFeatures());
        features.add(feature);
        character.setFeatures(features);
        CharacterUpdates.appendFeaturesGained(character, index, List.of(feature));
        CharacterUpdates.recordChoices(character, index, c -> c.withFightingStyle(chosen));
    }

    private static Set<String> fightingStylesTaken(PlayerCharacter character) {
        Set<String> styles = new LinkedHashSet<>();
        for (LevelHistoryEntry entry : orEmpty(character.getLevelHistory())) {
            if (entry.choices() != null && entry.choices().fightingStyle() != null) {
                styles.add(entry.choices().fightingStyle());
            }
        }
        return styles;
    }

    // The style's text, from the optional features data, which lists a style once for each class that has it
    private String fightingStyleDescription(String style) {
        return optionalFeatureRepository.findByFeatureTypeOrderByNameAsc(FIGHTING_STYLE_FEATURES).stream()
                .filter(feature -> style.equalsIgnoreCase(feature.getName()) && feature.getDescription() != null)
                .map(OptionalFeature::getDescription)
                .findFirst()
                .orElse("Fighting Style");
    }

    // ── Metamagic ────────────────────────────────────────────────

    /**
     * Metamagic options from the class's list, as many as the choice counts, none the character knows already (PHB
     * p.102: "you gain two of the following Metamagic options of your choice", and another at 10th and 17th level),
     * each a feature of the class named "Metamagic: X" that its level records, so levelling down takes them away.
     */
    private void applyMetamagic(PlayerCharacter character, PendingChoice choice, int index, List<String> picked) {
        List<String> options = choice.options() != null ? choice.options()
                : ClassProgression.metamagicOptions(classTables, choice.className());
        Set<String> known = metamagicKnown(character);
        List<String> chosen = new ArrayList<>();
        for (String option : distinct(orEmpty(picked))) {
            String name = options.stream().filter(o -> o.equalsIgnoreCase(option.trim())).findFirst()
                    .orElseThrow(() -> new ValidationException("A " + choice.className() + " chooses Metamagic from "
                            + String.join(", ", options) + ", not " + option.trim()));
            if (known.contains(name)) {
                throw new ValidationException(character.getName() + " already knows " + name);
            }
            chosen.add(name);
        }
        int needed = (int) Math.min(choice.count(), options.stream().filter(o -> !known.contains(o)).count());
        if (chosen.size() != needed) {
            throw new ValidationException("Choose " + needed + " Metamagic option" + (needed == 1 ? "" : "s")
                    + ", not " + chosen.size());
        }
        List<Feature> gained = chosen.stream()
                .map(name -> new Feature(Metamagic.FEATURE_PREFIX + name, metamagicDescription(name),
                        choice.className()))
                .toList();
        List<Feature> features = copy(character.getFeatures());
        features.addAll(gained);
        character.setFeatures(features);
        CharacterUpdates.appendFeaturesGained(character, index, gained);
        CharacterUpdates.recordChoices(character, index, c -> c.withMetamagic(chosen));
    }

    // The Metamagic options the character knows, by the features its levels gave
    private static Set<String> metamagicKnown(PlayerCharacter character) {
        Set<String> known = new LinkedHashSet<>();
        for (Feature feature : orEmpty(character.getFeatures())) {
            if (feature != null && feature.name() != null && feature.name().startsWith(Metamagic.FEATURE_PREFIX)) {
                known.add(feature.name().substring(Metamagic.FEATURE_PREFIX.length()));
            }
        }
        return known;
    }

    // The option's text, from the optional features data
    private String metamagicDescription(String option) {
        return optionalFeatureRepository.findByFeatureTypeOrderByNameAsc(METAMAGIC_FEATURES).stream()
                .filter(feature -> option.equalsIgnoreCase(feature.getName()) && feature.getDescription() != null)
                .map(OptionalFeature::getDescription)
                .findFirst()
                .orElse("Metamagic");
    }

    // ── A subclass's bonus proficiencies of the player's choice ──

    /**
     * Bonus proficiencies from those the choice offers, as many as it counts, none the character has already, such as
     * the College of Lore's three skills (PHB p.54), or fewer when fewer are left. Those the sheet lacks are added and
     * recorded on the level's {@code proficienciesGained}, so levelling down takes them away. For a choice whose skills
     * gain Expertise too, as the Knowledge Domain's do (PHB p.59: "your proficiency bonus is doubled"), a skill the
     * character is proficient in already may be chosen, one with Expertise may not, and the Expertise is recorded on
     * the level as an EXPERTISE choice's is.
     */
    private static void applyProficiencies(PlayerCharacter character, PendingChoice choice, int index,
                                           List<String> picked) {
        ProficienciesGained offered = choice.proficiencyOptions();
        List<SubclassProficiencies.Option> options = offered != null ? SubclassProficiencies.optionsOf(offered)
                : List.of();
        if (options.isEmpty()) {
            throw new ConflictException("This proficiency choice offers nothing to choose");
        }
        boolean expertise = Boolean.TRUE.equals(choice.expertise());
        ProficienciesGained held = SubclassProficiencies.sheet(character);
        List<String> expert = orEmpty(character.getSkillExpertises());
        Predicate<SubclassProficiencies.Option> taken = option -> expertise
                ? containsIgnoringCase(expert, option.name()) : SubclassProficiencies.holds(held, option);

        List<SubclassProficiencies.Option> chosen = new ArrayList<>();
        for (String name : distinct(orEmpty(picked))) {
            SubclassProficiencies.Option option = SubclassProficiencies.find(offered, name)
                    .orElseThrow(() -> new ValidationException("Choose from " + String.join(", ", options.stream()
                            .map(SubclassProficiencies.Option::name).toList()) + ", not " + name));
            if (taken.test(option)) {
                throw new ValidationException(character.getName() + (expertise ? " already has Expertise in "
                        : " already has ") + option.name());
            }
            chosen.add(option);
        }
        int needed = (int) Math.min(choice.count(), options.stream().filter(taken.negate()).count());
        if (chosen.size() != needed) {
            throw new ValidationException("Choose " + needed + " of the proficiencies offered, not " + chosen.size());
        }

        List<SubclassProficiencies.Option> lacking = chosen.stream()
                .filter(option -> !SubclassProficiencies.holds(held, option))
                .toList();
        CharacterUpdates.addProficiencies(character, index, SubclassProficiencies.grants(lacking));
        List<String> names = chosen.stream().map(SubclassProficiencies.Option::name).toList();
        if (expertise) {
            // A skill the sheet had already keeps its spelling there
            List<String> skills = names.stream()
                    .map(name -> orEmpty(character.getSkillProficiencies()).stream()
                            .filter(p -> p != null && p.trim().equalsIgnoreCase(name)).findFirst().orElse(name))
                    .toList();
            character.setSkillExpertises(CharacterUpdates.merged(character.getSkillExpertises(), skills));
            CharacterUpdates.recordExpertise(character, index, skills);
        }
        CharacterUpdates.recordChoices(character, index, c -> c.withProficiencies(
                List.copyOf(CharacterUpdates.merged(c.proficiencies(), names))));
    }

    /**
     * The PROFICIENCY choices a subclass's bonus proficiencies of the player's choice owe, one for each choice it
     * gives, on the level that records the subclass.
     */
    private List<PendingChoice> proficiencyChoices(Subclass subclass, int characterLevel, UUID classId,
                                                   String className, Integer classLevel, Source source) {
        return SubclassProficiencies.choices(objectMapper, subclass).stream()
                .map(c -> new PendingChoice(UUID.randomUUID(), Type.PROFICIENCY, source, characterLevel, classId,
                        className, classLevel, c.count(), null, null, null, null, c.options(),
                        c.expertise() ? Boolean.TRUE : null))
                .toList();
    }

    // ── The choices levels bring ─────────────────────────────────

    /**
     * Adds the choices a character created at its level owes: those of each level from the 1st, less what the
     * request chose itself, and the feats its race and background give. A subclass the request names is recorded on
     * its class's subclass level, which also owes the bonus proficiencies the subclass lets the player choose,
     * Expertise on the levels that give it, oldest first, and the class cantrips and
     * spells the request gives cover the CANTRIPS and SPELLS choices of their class oldest first; the creation wizard
     * gives those of the 1st level only, so the rest are made on the sheet.
     *
     * @throws ValidationException for a subclass of a class below its subclass level
     */
    void owedAtCreation(PlayerCharacter character, CharacterCreateRequest request) {
        Map<UUID, CharacterClass> classes = new HashMap<>();
        List<PendingChoice> owed = new ArrayList<>();
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            CharacterClass characterClass = characterClass(entry.classId(), entry.className());
            classes.put(entry.classId(), characterClass);
            if (entry.subclassId() == null) continue;
            int subclassLevel = subclassLevel(characterClass);
            if (entry.level() < subclassLevel) {
                throw new ValidationException("A " + characterClass.getName() + " chooses its subclass at level "
                        + subclassLevel + ", so a level " + entry.level() + " " + characterClass.getName()
                        + " has none yet");
            }
            int index = historyIndexOf(character, entry.classId(), characterClass.getName(), subclassLevel);
            if (index >= 0) {
                CharacterUpdates.recordChoices(character, index, c -> c.withSubclass(
                        new SubclassChosen(entry.classId(), entry.subclassId(), entry.subclassName())));
            }
            Subclass subclass = subclassRepository.findById(entry.subclassId())
                    .orElseThrow(() -> new ValidationException("Subclass not found: " + entry.subclassId()));
            grantSubclassProficiencies(character, subclass, index);
            if (index >= 0) {
                owed.addAll(proficiencyChoices(subclass, character.getLevelHistory().get(index).characterLevel(),
                        entry.classId(), characterClass.getName(), subclassLevel, Source.CREATION));
            }
        }

        for (LevelHistoryEntry level : orEmpty(character.getLevelHistory())) {
            CharacterClass characterClass = level.classId() != null ? classes.get(level.classId()) : null;
            if (characterClass == null) continue;
            owed.addAll(PendingChoices.owedAt(classTables, level.characterLevel(),
                    classLevel(character, characterClass, level.classLevel()), Source.CREATION));
        }
        owed = expertiseCovered(character, owed, request.getSkillExpertises());
        owed = spellsCovered(character, owed);
        owed.addAll(creationFeats(character));
        owed.sort(PendingChoices.ORDER);
        character.setPendingChoices(owed);
    }

    /** Adds the choices a level just gained owes. */
    void owedAtLevelUp(PlayerCharacter character, CharacterClass levelClass, int characterLevel, int classLevel) {
        List<PendingChoice> pending = copy(character.getPendingChoices());
        pending.addAll(PendingChoices.owedAt(classTables, characterLevel,
                classLevel(character, levelClass, classLevel), Source.LEVEL_UP));
        pending.sort(PendingChoices.ORDER);
        character.setPendingChoices(pending);
    }

    /**
     * Undoes a level's choices as levelling down removes the level: those made for it are reversed, by what the
     * level records, and those still pending go.
     */
    void levelRemoved(PlayerCharacter character, LevelHistoryEntry level) {
        reverse(character, level);
        List<PendingChoice> pending = copy(character.getPendingChoices());
        pending.removeIf(choice -> choice.characterLevel() == level.characterLevel());
        character.setPendingChoices(pending);
    }

    // Reverses what a level's choices recorded: Expertise, the Ability Score Improvement by the amount applied (or,
    // for one recorded before R6, the amount requested), the feats a race or background gave, the cantrips and spells
    // learned, and the subclass. A Fighting Style's feature goes with the level's features gained.
    private void reverse(PlayerCharacter character, LevelHistoryEntry level) {
        LevelChoices choices = level.choices();
        if (choices == null) return;
        if (choices.expertise() != null && !choices.expertise().isEmpty()) {
            character.setSkillExpertises(CharacterUpdates.without(character.getSkillExpertises(), choices.expertise()));
        }
        AbilityScoreImprovement asi = choices.asi();
        if (asi != null && "ability".equals(asi.type())) {
            for (AbilityBonus increase : asi.applied() != null ? asi.applied() : orEmpty(asi.increases())) {
                CharacterUpdates.decreaseAbility(character, increase.ability(), increase.bonus());
            }
        } else if (asi != null && "feat".equals(asi.type())) {
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
        for (FeatTaken feat : orEmpty(choices.feats()).reversed()) {
            featEffectResolver.reverseFeatEffects(character, feat.appliedEffects());
        }
        if (level.className() != null) {
            CharacterSpellService.forget(character, level.className(), choices.cantrips(), choices.spells());
        }
        if (choices.subclass() != null) {
            UUID classId = choices.subclass().classId();
            CharacterUpdates.clearClassEntrySubclass(character, classId);
            if (character.getClassRef() != null && classId.equals(character.getClassRef().getId())) {
                character.setSubclassRef(null);
                character.setSubclass(null);
            }
        }
    }

    // ── The class's spellcasting ability ─────────────────────────

    /**
     * Stores the spellcasting ability of the character's first casting class (D159), and records it on the level that
     * gives the class its spellcasting: the class's 1st level, or for an Eldritch Knight or Arcane Trickster the
     * subclass's level, so levelling down past that level works it out again. Creation calls this when the race
     * chose no ability, levelling up when the character has none, as when it first takes a casting class, and
     * choosing an Eldritch Knight or Arcane Trickster when the character has none. Stores nothing for a character
     * whose classes don't cast.
     *
     * @return whether it stored one
     */
    boolean storeClassSpellcastingAbility(PlayerCharacter character) {
        ClassSpellcastingAbility found = classSpellcastingAbility(character);
        if (found == null) return false;
        character.setSpellcastingAbility(found.ability());
        if (found.levelIndex() >= 0) {
            CharacterUpdates.recordChoices(character, found.levelIndex(), c -> c.withSetsSpellcastingAbility(true));
        }
        return true;
    }

    /**
     * What {@link #storeClassSpellcastingAbility} would store and on which level it would record it; changes
     * nothing. Null for a character whose classes don't cast.
     */
    ClassSpellcastingAbility classSpellcastingAbility(PlayerCharacter character) {
        String ability = characterStats.classSpellcastingAbility(character);
        if (ability == null) return null;
        ClassEntry entry = characterStats.classSpellcastingEntry(character);
        CharacterClass characterClass = characterClass(entry.classId(), entry.className());
        int givingLevel = Boolean.TRUE.equals(characterClass.getIsSpellcaster()) ? 1 : subclassLevel(characterClass);
        int index = historyIndexOf(character, entry.classId(), entry.className(), givingLevel);
        return new ClassSpellcastingAbility(ability, characterClass.getName(), givingLevel, index);
    }

    /**
     * A class's spellcasting ability as the character would store it.
     *
     * @param className   the first of the character's classes that casts
     * @param classLevel  the class level that gives it its spellcasting
     * @param levelIndex  the index of that level's history entry, or -1 when the history has none
     */
    record ClassSpellcastingAbility(String ability, String className, int classLevel, int levelIndex) {}

    /**
     * Works the spellcasting ability out again once levelling down has removed the level that stored its class's,
     * after the class entries have lost that level (D159): another casting class's, recorded on its level, or else
     * the first feat's still applied that chose one, or none.
     */
    void classSpellcastingAbilityRemoved(PlayerCharacter character) {
        character.setSpellcastingAbility(null);
        if (!storeClassSpellcastingAbility(character)) {
            featEffectResolver.handOnSpellcastingAbility(character);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ── The backfill ─────────────────────────────────────────────

    // ── A subclass's bonus proficiencies ─────────────────────────

    /**
     * Gives the character the bonus proficiencies the subclass grants that it lacks, such as a Life Domain's heavy
     * armour (PHB p.60), recorded on the level history entry at the index, the level that records the subclass, so
     * levelling down past it takes them away with the subclass; recorded nowhere for an index below 0.
     */
    private void grantSubclassProficiencies(PlayerCharacter character, Subclass subclass, int index) {
        ProficienciesGained missing = SubclassProficiencies.missing(character,
                SubclassProficiencies.of(objectMapper, subclass));
        CharacterUpdates.addProficiencies(character, index, missing);
    }

    /**
     * The bonus proficiencies the character's subclasses grant that its sheet lacks, as characters given a subclass
     * before they were granted lack them, for the recompute-characters job; one entry per subclass with any missing.
     * Changes nothing.
     *
     * @throws ConflictException when a subclass the character has can't be found or read
     */
    public List<MissingSubclassProficiencies> missingSubclassProficiencies(PlayerCharacter character) {
        List<MissingSubclassProficiencies> found = new ArrayList<>();
        ProficienciesGained held = SubclassProficiencies.sheet(character);
        for (ClassEntry entry : subclassEntries(character)) {
            Subclass subclass = subclassRepository.findById(entry.subclassId())
                    .orElseThrow(() -> new ConflictException("The subclass " + entry.subclassName() + " of "
                            + character.getName() + "'s " + entry.className() + " can't be found"));
            ProficienciesGained missing = SubclassProficiencies.missing(held,
                    SubclassProficiencies.of(objectMapper, subclass));
            if (!CharacterUpdates.addedAny(missing)) continue;
            held = SubclassProficiencies.plus(held, missing);
            int index = subclassRecordIndex(character, entry.classId());
            LevelHistoryEntry level = index >= 0 ? character.getLevelHistory().get(index) : null;
            found.add(new MissingSubclassProficiencies(subclass.getName(), missing,
                    SubclassProficiencies.describe(missing), level != null ? level.className() : null,
                    level != null ? level.classLevel() : 0, index));
        }
        return found;
    }

    /**
     * Gives the character what {@link #missingSubclassProficiencies} finds, each recorded on the level that records
     * its subclass when the history has one, for the recompute-characters job; the caller saves the character.
     */
    public void storeMissingSubclassProficiencies(PlayerCharacter character) {
        for (MissingSubclassProficiencies missing : missingSubclassProficiencies(character)) {
            CharacterUpdates.addProficiencies(character, missing.levelIndex(), missing.missing());
        }
    }

    /**
     * Bonus proficiencies a character's subclass grants and its sheet lacks.
     *
     * @param subclassName the subclass
     * @param missing      what the sheet lacks
     * @param description  what is missing, such as "armour heavy; weapons martial"
     * @param className    the class of the level that records the subclass; null when no level records it
     * @param classLevel   that level's class level; 0 when no level records the subclass
     * @param levelIndex   that level's place in the level history; below 0 when none, so levelling down won't take
     *                     them away
     */
    public record MissingSubclassProficiencies(String subclassName, ProficienciesGained missing, String description,
                                               String className, int classLevel, int levelIndex) {

        /** Whether a level records them, so levelling down past it takes them away. */
        public boolean recorded() {
            return levelIndex >= 0;
        }
    }

    // The class entries with a subclass, or for a character without entries its own class's subclass
    private static List<ClassEntry> subclassEntries(PlayerCharacter character) {
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        if (entries.isEmpty() && character.getSubclassRef() != null) {
            Subclass subclass = character.getSubclassRef();
            CharacterClass characterClass = subclass.getCharacterClass();
            return List.of(new ClassEntry(characterClass != null ? characterClass.getId() : null,
                    characterClass != null ? characterClass.getName() : character.getCharacterClass(),
                    subclass.getId(), subclass.getName(), character.getLevel() != null ? character.getLevel() : 0));
        }
        return entries.stream().filter(entry -> entry != null && entry.subclassId() != null).toList();
    }

    // The level history entry that records the class's subclass choice; -1 for none
    private static int subclassRecordIndex(PlayerCharacter character, UUID classId) {
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        for (int i = 0; i < history.size(); i++) {
            LevelChoices choices = history.get(i).choices();
            if (choices != null && choices.subclass() != null && classId != null
                    && classId.equals(choices.subclass().classId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The Ability Score Improvements, subclasses, Expertise, Metamagic options and subclass bonus proficiencies of the
     * player's choice the character's levels owe that no level records and no Pending Choice holds, as characters
     * levelled before R6 workstream F (or, for Metamagic, before M18, and for the proficiencies before they were owed)
     * can owe them, for the backfill-pending-choices job; it adds them through {@link #addPending}.
     * Expertise the sheet has that no level records, as the creation wizard gave it before, counts against the levels
     * that give Expertise, oldest first, and Metamagic features no level records against the Metamagic levels.
     * Changes nothing.
     *
     * @throws ConflictException when a class the character has can't be found
     */
    public List<PendingChoice> missedChoices(PlayerCharacter character) {
        List<PendingChoice> pending = orEmpty(character.getPendingChoices());
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        List<PendingChoice> missed = new ArrayList<>();
        for (LevelHistoryEntry level : history) {
            if (level.className() == null
                    || !ClassProgression.isAsiLevel(classTables, level.className(), level.classLevel())) {
                continue;
            }
            boolean recorded = level.choices() != null && level.choices().asi() != null;
            if (!recorded && !isPending(pending, Type.ASI, level.characterLevel())) {
                missed.add(missedChoice(Type.ASI, level, 1, null));
            }
        }
        for (ClassEntry entry : orEmpty(character.getMulticlassEntries())) {
            if (entry.subclassId() != null) continue;
            CharacterClass characterClass = characterClass(entry.classId(), entry.className());
            int subclassLevel = subclassLevel(characterClass);
            List<String> options = subclassOptions(characterClass);
            boolean pendingSubclass = pending.stream()
                    .anyMatch(c -> c.type() == Type.SUBCLASS && entry.classId().equals(c.classId()));
            int index = historyIndexOf(character, entry.classId(), characterClass.getName(), subclassLevel);
            if (entry.level() >= subclassLevel && !options.isEmpty() && !pendingSubclass && index >= 0) {
                missed.add(missedChoice(Type.SUBCLASS, history.get(index), 1, options));
            }
        }
        int unrecorded = unrecordedExpertise(character);
        for (LevelHistoryEntry level : history) {
            int count = level.className() != null
                    ? ClassProgression.expertiseChoices(classTables, level.className(), level.classLevel()) : 0;
            boolean recorded = level.choices() != null && level.choices().expertise() != null;
            if (count == 0 || recorded || isPending(pending, Type.EXPERTISE, level.characterLevel())) continue;
            int covered = Math.min(count, unrecorded);
            unrecorded -= covered;
            if (covered < count) {
                missed.add(missedChoice(Type.EXPERTISE, level, count - covered, null));
            }
        }
        int unrecordedMetamagic = unrecordedMetamagic(character);
        for (LevelHistoryEntry level : history) {
            int count = level.className() != null
                    ? ClassProgression.metamagicChoices(classTables, level.className(), level.classLevel()) : 0;
            boolean recorded = level.choices() != null && level.choices().metamagic() != null;
            if (count == 0 || recorded || isPending(pending, Type.METAMAGIC, level.characterLevel())) continue;
            int covered = Math.min(count, unrecordedMetamagic);
            unrecordedMetamagic -= covered;
            if (covered < count) {
                missed.add(missedChoice(Type.METAMAGIC, level, count - covered,
                        ClassProgression.metamagicOptions(classTables, level.className())));
            }
        }
        missed.addAll(missedProficiencyChoices(character, pending));
        missed.sort(PendingChoices.ORDER);
        return missed;
    }

    // The PROFICIENCY choices of each subclass the character has that gives bonus proficiencies of the player's choice,
    // when the level that records the subclass, or else the class's subclass level, records none made and none is
    // pending there, as for a subclass taken before they were owed
    private List<PendingChoice> missedProficiencyChoices(PlayerCharacter character, List<PendingChoice> pending) {
        List<PendingChoice> missed = new ArrayList<>();
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        for (ClassEntry entry : subclassEntries(character)) {
            Subclass subclass = subclassRepository.findById(entry.subclassId())
                    .orElseThrow(() -> new ConflictException("The subclass " + entry.subclassName() + " of "
                            + character.getName() + "'s " + entry.className() + " can't be found"));
            if (SubclassProficiencies.choices(objectMapper, subclass).isEmpty()) continue;
            int index = subclassRecordIndex(character, entry.classId());
            if (index < 0 && subclass.getCharacterClass() != null) {
                index = historyIndexOf(character, entry.classId(), entry.className(),
                        subclassLevel(subclass.getCharacterClass()));
            }
            if (index < 0) continue;
            LevelHistoryEntry level = history.get(index);
            boolean made = level.choices() != null && level.choices().proficiencies() != null;
            if (made || isPending(pending, Type.PROFICIENCY, level.characterLevel())) continue;
            missed.addAll(proficiencyChoices(subclass, level.characterLevel(), level.classId(), level.className(),
                    level.classLevel(), Source.LEVEL_UP));
        }
        return missed;
    }

    // The Metamagic options the sheet has as features that no level records, as one added by hand would be
    private static int unrecordedMetamagic(PlayerCharacter character) {
        Set<String> unrecorded = new LinkedHashSet<>();
        metamagicKnown(character).forEach(option -> unrecorded.add(key(option)));
        for (LevelHistoryEntry level : orEmpty(character.getLevelHistory())) {
            if (level.choices() != null) {
                orEmpty(level.choices().metamagic()).forEach(option -> unrecorded.remove(key(option)));
            }
        }
        return unrecorded.size();
    }

    /** Adds choices to the character's Pending Choices, in the order they are made. */
    public void addPending(PlayerCharacter character, List<PendingChoice> choices) {
        List<PendingChoice> pending = copy(character.getPendingChoices());
        pending.addAll(choices);
        pending.sort(PendingChoices.ORDER);
        character.setPendingChoices(pending);
    }

    private static PendingChoice missedChoice(Type type, LevelHistoryEntry level, int count, List<String> options) {
        return new PendingChoice(UUID.randomUUID(), type, Source.LEVEL_UP, level.characterLevel(), level.classId(),
                level.className(), level.classLevel(), count, options, null, null, null);
    }

    private static boolean isPending(List<PendingChoice> pending, Type type, int characterLevel) {
        return pending.stream().anyMatch(c -> c.type() == type && c.characterLevel() == characterLevel);
    }

    // The skills the sheet has Expertise in that no level records and no feat added
    private static int unrecordedExpertise(PlayerCharacter character) {
        Set<String> unrecorded = new LinkedHashSet<>();
        orEmpty(character.getSkillExpertises()).forEach(skill -> unrecorded.add(key(skill)));
        for (LevelHistoryEntry level : orEmpty(character.getLevelHistory())) {
            if (level.choices() != null) {
                orEmpty(level.choices().expertise()).forEach(skill -> unrecorded.remove(key(skill)));
            }
        }
        for (AppliedFeatEffects feat : DmAdjustments.featsTaken(character)) {
            orEmpty(feat.expertiseAdded()).forEach(skill -> unrecorded.remove(key(skill)));
        }
        return unrecorded.size();
    }

    // ── What creation chose ──────────────────────────────────────

    // The EXPERTISE choices less the skills the request gave Expertise, recorded on the levels they cover, oldest
    // first; a skill beyond what the levels give stays on the sheet unrecorded, as the creation request's
    // proficiencies aren't checked until R8
    private static List<PendingChoice> expertiseCovered(PlayerCharacter character, List<PendingChoice> owed,
                                                        List<String> given) {
        List<String> skills = new ArrayList<>(distinct(orEmpty(given)));
        List<PendingChoice> left = new ArrayList<>();
        for (PendingChoice choice : owed) {
            if (choice.type() != Type.EXPERTISE || skills.isEmpty()) {
                left.add(choice);
                continue;
            }
            int covered = Math.min(choice.count(), skills.size());
            List<String> taken = new ArrayList<>(skills.subList(0, covered));
            skills.subList(0, covered).clear();
            CharacterUpdates.recordExpertise(character, CharacterUpdates.historyIndex(character,
                    choice.characterLevel()), taken);
            if (covered < choice.count()) {
                left.add(choice.withCount(choice.count() - covered));
            }
        }
        return left;
    }

    // The CANTRIPS and SPELLS choices less the class cantrips and spells the character was created with, by how many
    // each class has, oldest first; a spell without a source counts as the first class's, as the sheet reads it
    private static List<PendingChoice> spellsCovered(PlayerCharacter character, List<PendingChoice> owed) {
        Map<String, Integer> cantrips = new HashMap<>();
        Map<String, Integer> spells = new HashMap<>();
        List<ClassEntry> entries = orEmpty(character.getMulticlassEntries());
        String firstClass = entries.isEmpty() ? character.getCharacterClass() : entries.getFirst().className();
        for (KnownSpell spell : orEmpty(character.getSpellsKnown())) {
            String source = spell.source() != null && !spell.source().isBlank() ? spell.source() : "class:" + firstClass;
            if (!source.startsWith("class:")) continue;
            String className = source.substring("class:".length());
            (spell.level() == 0 ? cantrips : spells).merge(className, 1, Integer::sum);
        }
        List<PendingChoice> left = new ArrayList<>();
        for (PendingChoice choice : owed) {
            Map<String, Integer> given = choice.type() == Type.CANTRIPS ? cantrips
                    : choice.type() == Type.SPELLS ? spells : null;
            int available = given != null ? given.getOrDefault(choice.className(), 0) : 0;
            if (available == 0) {
                left.add(choice);
                continue;
            }
            int covered = Math.min(choice.count(), available);
            given.put(choice.className(), available - covered);
            if (covered < choice.count()) {
                left.add(choice.withCount(choice.count() - covered));
            }
        }
        return left;
    }

    // A FEAT choice for each feat the race gives, such as a Variant Human's, and one for the background's, which
    // offers the feats it lists
    private List<PendingChoice> creationFeats(PlayerCharacter character) {
        List<PendingChoice> feats = new ArrayList<>();
        Race race = character.getRaceRef();
        JsonNode raceChoices = race != null ? readJson(race.getRaceChoices(), "race " + race.getName()) : null;
        int raceFeats = raceChoices != null ? raceChoices.path("feats").asInt(0) : 0;
        for (int i = 0; i < raceFeats; i++) {
            feats.add(new PendingChoice(UUID.randomUUID(), Type.FEAT, Source.RACE, 1, null, null, null, 1, null,
                    null, null, null));
        }
        Background background = character.getBackgroundRef();
        JsonNode listed = background != null ? readJson(background.getFeats(), "background " + background.getName())
                : null;
        List<String> offered = new ArrayList<>();
        if (listed != null && listed.isArray()) {
            for (JsonNode name : listed) {
                if (name.isString() && !name.asString("").isBlank()) {
                    offered.add(featRepository.findByNameIgnoreCase(name.asString("").trim())
                            .map(Feat::getName)
                            .orElse(name.asString("").trim()));
                }
            }
        }
        if (!offered.isEmpty()) {
            feats.add(new PendingChoice(UUID.randomUUID(), Type.FEAT, Source.BACKGROUND, 1, null, null, null, 1,
                    offered, null, null, null));
        }
        return feats;
    }

    // Reference data that can't be read is a 409, not a character without its choices (D155)
    private JsonNode readJson(String json, String what) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new ConflictException("The data of the " + what + " can't be read");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    // A class level of the character's, with the subclass its class has and the subclasses it chooses from
    private PendingChoices.ClassLevel classLevel(PlayerCharacter character, CharacterClass characterClass,
                                                 int classLevel) {
        UUID subclassId = CharacterUpdates.subclassIdOf(character, characterClass.getId());
        String subclassName = subclassId != null
                ? orEmpty(character.getMulticlassEntries()).stream()
                        .filter(e -> characterClass.getId().equals(e.classId()))
                        .map(ClassEntry::subclassName)
                        .findFirst().orElse(null)
                : null;
        return new PendingChoices.ClassLevel(characterClass.getId(), characterClass.getName(), classLevel,
                subclassName, subclassLevel(characterClass), subclassOptions(characterClass));
    }

    private List<String> subclassOptions(CharacterClass characterClass) {
        return subclassRepository.findByCharacterClassIdOrderByNameAsc(characterClass.getId()).stream()
                .map(Subclass::getName)
                .toList();
    }

    private CharacterClass characterClass(UUID classId, String className) {
        return classRepository.findById(classId)
                .orElseThrow(() -> new ConflictException("The class " + className
                        + " that the character has can't be found"));
    }

    private static int subclassLevel(CharacterClass characterClass) {
        return characterClass.getSubclassLevel() != null ? characterClass.getSubclassLevel() : NO_SUBCLASS_LEVEL;
    }

    // The index of the level history entry of a class level, by the class's ID or, for an entry without one, its name
    private static int historyIndexOf(PlayerCharacter character, UUID classId, String className, int classLevel) {
        List<LevelHistoryEntry> history = orEmpty(character.getLevelHistory());
        for (int i = 0; i < history.size(); i++) {
            LevelHistoryEntry entry = history.get(i);
            boolean sameClass = entry.classId() != null ? entry.classId().equals(classId)
                    : className != null && className.equals(entry.className());
            if (sameClass && entry.classLevel() == classLevel) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsIgnoringCase(List<String> names, String name) {
        String wanted = name.trim();
        return names.stream().anyMatch(n -> n != null && n.trim().equalsIgnoreCase(wanted));
    }

    // The names given, trimmed, each once whatever its case
    private static List<String> distinct(List<String> names) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                distinct.putIfAbsent(key(name), name.trim());
            }
        }
        return new ArrayList<>(distinct.values());
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
