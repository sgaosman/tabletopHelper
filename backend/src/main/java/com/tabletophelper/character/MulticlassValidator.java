package com.tabletophelper.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabletophelper.character.dto.EligibleClassResponse;
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

        Map<String, Integer> currentClassLevels = parseMulticlassEntries(character.getMulticlassEntries());
        Set<String> currentClassIds = currentClassLevels.keySet();

        List<EligibleClassResponse> results = new ArrayList<>();

        for (CharacterClass cc : allClasses) {
            String classIdStr = cc.getId().toString();
            boolean isCurrent = currentClassIds.contains(classIdStr);
            int classLevel = currentClassLevels.getOrDefault(classIdStr, 0);

            boolean meetsCurrent = true;
            if (!isCurrent) {
                for (String currentId : currentClassIds) {
                    CharacterClass currentClass = allClasses.stream()
                            .filter(c -> c.getId().toString().equals(currentId))
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
                    .isCurrentClass(isCurrent)
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

    static Map<String, Integer> parseMulticlassEntries(String multiclassEntriesJson) {
        if (multiclassEntriesJson == null || multiclassEntriesJson.isBlank()) return Map.of();
        try {
            List<Map<String, Object>> entries = mapper.readValue(
                    multiclassEntriesJson, new TypeReference<>() {});
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map<String, Object> entry : entries) {
                String classId = (String) entry.get("classId");
                int level = entry.get("level") instanceof Number n ? n.intValue() : 1;
                result.put(classId, level);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
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
