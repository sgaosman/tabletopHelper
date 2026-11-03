package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.EligibleClassResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.rules.Ability;
import com.tabletophelper.rules.character.Multiclassing;
import com.tabletophelper.shared.ConflictException;

import java.util.*;

/**
 * Multiclassing prerequisites as the class data records them, checked with rules.character.Multiclassing (PHB
 * p.163): a new class needs its own prerequisites and those of every class the character already has. Class data
 * that can't be read is a 409, never "no prerequisites" (D155).
 */
public class MulticlassValidator {

    private static final ObjectMapper mapper = new ObjectMapper();

    public record Requirement(String ability, int minimum, String operator) {}

    /**
     * The requirements in a class's multiclass_requirements: none for no text.
     *
     * @throws ConflictException when the text can't be read
     */
    public static List<Requirement> parseRequirements(String requirementsJson) {
        if (requirementsJson == null || requirementsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = mapper.readValue(requirementsJson, new TypeReference<>() {});
            return raw.stream().map(m -> new Requirement(
                    (String) m.get("ability"),
                    m.get("minimum") instanceof Number n ? n.intValue() : 13,
                    (String) m.getOrDefault("operator", "AND")
            )).toList();
        } catch (Exception e) {
            throw new ConflictException("A class's multiclassing prerequisites can't be read: " + requirementsJson);
        }
    }

    /**
     * The prerequisites the requirements give: every minimum met, or any one when an OR is among them.
     *
     * @throws ConflictException when they can't be read, or name no ability
     */
    public static Multiclassing.Prerequisites prerequisites(String requirementsJson) {
        List<Requirement> requirements = parseRequirements(requirementsJson);
        Map<Ability, Integer> minimums = new EnumMap<>(Ability.class);
        for (Requirement requirement : requirements) {
            Ability ability = Ability.parse(requirement.ability()).orElseThrow(() -> new ConflictException(
                    "A class's multiclassing prerequisites name no ability: " + requirementsJson));
            minimums.merge(ability, requirement.minimum(), Math::max);
        }
        boolean any = requirements.stream().anyMatch(r -> "OR".equals(r.operator()));
        return any ? Multiclassing.Prerequisites.anyOf(minimums) : Multiclassing.Prerequisites.allOf(minimums);
    }

    public static boolean meetsPrerequisites(String requirementsJson, PlayerCharacter character) {
        return prerequisites(requirementsJson).metBy(scores(character));
    }

    /**
     * The classes whose prerequisites stop the character taking a level in a class: the new class first, then each
     * class it already has; empty when it may, which it always may in a class it has.
     */
    public static List<String> unmetPrerequisites(PlayerCharacter character, CharacterClass newClass,
                                                  List<CharacterClass> currentClasses) {
        Map<String, Multiclassing.Prerequisites> current = new LinkedHashMap<>();
        for (CharacterClass currentClass : currentClasses) {
            current.put(currentClass.getName(), prerequisites(currentClass.getMulticlassRequirements()));
        }
        return Multiclassing.unmetPrerequisites(scores(character), current, newClass.getName(),
                prerequisites(newClass.getMulticlassRequirements()));
    }

    public static String describePrerequisites(String requirementsJson, PlayerCharacter character) {
        List<Requirement> reqs = parseRequirements(requirementsJson);
        if (reqs.isEmpty()) return "No requirements";

        boolean hasOr = reqs.stream().anyMatch(r -> "OR".equals(r.operator()));
        String joiner = hasOr ? " or " : " and ";

        return reqs.stream()
                .map(r -> {
                    int score = getAbilityScore(r.ability(), character);
                    String check = score >= r.minimum() ? "✓" : "✗";
                    return r.ability() + " " + r.minimum() + " (you have " + score + " " + check + ")";
                })
                .reduce((a, b) -> a + joiner + b)
                .orElse("");
    }

    public static List<EligibleClassResponse> getEligibleClasses(
            PlayerCharacter character, List<CharacterClass> allClasses) {

        Map<UUID, Integer> currentClassLevels = classLevels(character.getMulticlassEntries());
        List<CharacterClass> currentClasses = allClasses.stream()
                .filter(c -> currentClassLevels.containsKey(c.getId()))
                .toList();

        List<EligibleClassResponse> results = new ArrayList<>();

        for (CharacterClass cc : allClasses) {
            boolean isCurrent = currentClassLevels.containsKey(cc.getId());
            int classLevel = currentClassLevels.getOrDefault(cc.getId(), 0);
            boolean eligible = isCurrent || unmetPrerequisites(character, cc, currentClasses).isEmpty();

            String description = isCurrent ? "Current class"
                    : describePrerequisites(cc.getMulticlassRequirements(), character);

            results.add(EligibleClassResponse.builder()
                    .classId(cc.getId())
                    .className(cc.getName())
                    .currentClassLevel(classLevel)
                    .currentClass(isCurrent)
                    .meetsPrerequisites(eligible)
                    .prerequisiteDescription(description)
                    .build());
        }

        results.sort(Comparator
                .<EligibleClassResponse, Boolean>comparing(r -> !r.isCurrentClass())
                .thenComparing(r -> !r.isMeetsPrerequisites())
                .thenComparing(EligibleClassResponse::getClassName));

        return results;
    }

    /** Each class the character has, by ID, and its level there; empty for none. */
    static Map<UUID, Integer> classLevels(List<ClassEntry> entries) {
        if (entries == null) return Map.of();
        Map<UUID, Integer> result = new LinkedHashMap<>();
        for (ClassEntry entry : entries) {
            result.put(entry.classId(), entry.level());
        }
        return result;
    }

    // The six scores as the rules read them; a missing score counts as 0
    private static Map<Ability, Integer> scores(PlayerCharacter character) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) {
            scores.put(ability, getAbilityScore(ability.name(), character));
        }
        return scores;
    }

    private static int getAbilityScore(String ability, PlayerCharacter character) {
        if (ability == null) return 0;
        return switch (ability.toUpperCase()) {
            case "STR" -> character.getStrength() != null ? character.getStrength() : 0;
            case "DEX" -> character.getDexterity() != null ? character.getDexterity() : 0;
            case "CON" -> character.getConstitution() != null ? character.getConstitution() : 0;
            case "INT" -> character.getIntelligence() != null ? character.getIntelligence() : 0;
            case "WIS" -> character.getWisdom() != null ? character.getWisdom() : 0;
            case "CHA" -> character.getCharisma() != null ? character.getCharisma() : 0;
            default -> 0;
        };
    }
}
