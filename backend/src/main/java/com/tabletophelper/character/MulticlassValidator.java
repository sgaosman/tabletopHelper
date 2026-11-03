package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.EligibleClassResponse;
import com.tabletophelper.character.state.ClassEntry;
import com.tabletophelper.reference.CharacterClass;

import java.util.*;

public class MulticlassValidator {

    private static final ObjectMapper mapper = new ObjectMapper();

    public record Requirement(String ability, int minimum, String operator) {}

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
            return List.of();
        }
    }

    public static boolean meetsPrerequisites(String requirementsJson, PlayerCharacter character) {
        List<Requirement> reqs = parseRequirements(requirementsJson);
        if (reqs.isEmpty()) return true;

        boolean hasOr = reqs.stream().anyMatch(r -> "OR".equals(r.operator()));
        if (hasOr) {
            return reqs.stream().anyMatch(r -> getAbilityScore(r.ability(), character) >= r.minimum());
        }

        return reqs.stream().allMatch(r -> getAbilityScore(r.ability(), character) >= r.minimum());
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
        Set<UUID> currentClassIds = currentClassLevels.keySet();

        List<EligibleClassResponse> results = new ArrayList<>();

        for (CharacterClass cc : allClasses) {
            boolean isCurrent = currentClassIds.contains(cc.getId());
            int classLevel = currentClassLevels.getOrDefault(cc.getId(), 0);

            boolean meetsCurrent = true;
            if (!isCurrent) {
                for (UUID currentId : currentClassIds) {
                    CharacterClass currentClass = allClasses.stream()
                            .filter(c -> c.getId().equals(currentId))
                            .findFirst().orElse(null);
                    if (currentClass != null) {
                        meetsCurrent = meetsCurrent &&
                                meetsPrerequisites(currentClass.getMulticlassRequirements(), character);
                    }
                }
            }

            boolean meetsNew = isCurrent || meetsPrerequisites(cc.getMulticlassRequirements(), character);
            boolean eligible = isCurrent || (meetsCurrent && meetsNew);

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
