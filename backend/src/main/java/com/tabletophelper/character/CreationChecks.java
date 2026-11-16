package com.tabletophelper.character;

import com.tabletophelper.reference.Background;
import com.tabletophelper.reference.CharacterClass;
import com.tabletophelper.reference.Race;
import com.tabletophelper.rules.character.ProficiencyLists;
import com.tabletophelper.rules.combat.RacialFeatures.DraconicAncestry;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a create request may give a character, checked against the reference data (D232; the security review D221
 * found creation copying these from the request unchecked). Only what the creation wizard can send passes:
 * <ul>
 *   <li>saving throws are worked out, never taken from the request: the first class's two (PHB p.164: multiclassing
 *       never adds saving throws). No feat is taken in the request, since a race's or background's feat is a Pending
 *       Choice made on the sheet (D159), so none adds one at creation</li>
 *   <li>damage resistances only as the race gives them: its own, one of each choice it offers, and a draconic
 *       ancestry's damage type (PHB p.34, D208). The rest come only from DM Adjustments (D158)</li>
 *   <li>skill proficiencies only as the race, the first class's list, each later class's multiclassing skill (PHB
 *       p.164) and the background give them, with a background skill already held replaced by any other (PHB p.125,
 *       D226)</li>
 * </ul>
 * Expertise and spells are checked where their Pending Choices are counted (PendingChoiceService and
 * CharacterSpellService).
 */
final class CreationChecks {

    private CreationChecks() {
    }

    // ── Saving throws ────────────────────────────────────────────

    /**
     * The saving throws a character created with these classes is proficient in: the first class's, as the class
     * data abbreviates them (PHB p.164: a class taken by multiclassing gives none, D232). None without a class.
     *
     * @throws ConflictException when the class's saving throws can't be read (D155)
     */
    static List<String> savingThrows(ObjectMapper objectMapper, CharacterClass firstClass) {
        if (firstClass == null) return List.of();
        JsonNode saves = read(objectMapper, firstClass.getSavingThrowProficiencies(),
                "saving throws of the class " + firstClass.getName());
        List<String> result = new ArrayList<>();
        if (saves != null && saves.isArray()) {
            for (JsonNode save : saves) {
                String name = save.asString("").trim();
                if (!name.isEmpty() && result.stream().noneMatch(name::equalsIgnoreCase)) result.add(name);
            }
        }
        return result;
    }

    // ── Damage resistances ───────────────────────────────────────

    /**
     * The damage resistances the request names, once each, when the race gives every one (D158, D232): those the race
     * data lists, one of each set it lets the player choose from, such as a Tiefling variant's, and the damage type
     * of the draconic ancestry the request names for a race with that trait (PHB p.34, D208). No subclass or feat
     * puts a resistance on the sheet at creation: a subclass's are features used in combat, and creation feats are
     * Pending Choices.
     *
     * @throws ValidationException naming a resistance the race doesn't give
     * @throws ConflictException   when the race's resistances can't be read (D155)
     */
    static List<String> damageResistances(ObjectMapper objectMapper, Race race, String draconicAncestry,
                                          List<String> requested) {
        List<String> wanted = distinct(requested);
        if (wanted.isEmpty()) return requested == null ? null : List.of();
        Set<String> fixed = new HashSet<>();
        List<Set<String>> choices = new ArrayList<>();
        if (race != null) {
            JsonNode listed = read(objectMapper, race.getResistances(), "resistances of the race " + race.getName());
            if (listed != null && listed.isArray()) {
                for (JsonNode entry : listed) {
                    String text = entry.asString("").trim();
                    if (text.isEmpty()) continue;
                    if (text.toLowerCase(Locale.ROOT).startsWith("choose:")) {
                        choices.add(keys(Arrays.asList(text.substring("choose:".length()).split(","))));
                    } else {
                        fixed.add(key(text));
                    }
                }
            }
            JsonNode raceChoices = read(objectMapper, race.getRaceChoices(), "choices of the race " + race.getName());
            if (raceChoices != null && choices.isEmpty()) {
                for (JsonNode choice : raceChoices.path("resistances")) {
                    List<String> from = new ArrayList<>();
                    choice.path("from").forEach(type -> from.add(type.asString("")));
                    if (!from.isEmpty()) choices.add(keys(from));
                }
            }
            if (DraconicAncestries.owedBy(race, objectMapper)) {
                DraconicAncestry.of(draconicAncestry).ifPresent(ancestry -> fixed.add(key(ancestry.damageType())));
            }
        }
        List<String> refused = new ArrayList<>();
        List<Set<String>> unused = new ArrayList<>(choices);
        for (String type : wanted) {
            if (fixed.contains(key(type))) continue;
            Optional<Set<String>> choice = unused.stream().filter(set -> set.contains(key(type))).findFirst();
            if (choice.isPresent()) {
                unused.remove(choice.get());
            } else {
                refused.add(type);
            }
        }
        if (!refused.isEmpty()) {
            throw new ValidationException("Resistance to " + String.join(", ", refused) + " isn't one the "
                    + (race != null ? race.getName() : "character's race") + " gives; a resistance beyond the race's "
                    + "comes from the DM's adjustments");
        }
        return wanted;
    }

    // ── Skill proficiencies ──────────────────────────────────────

    /**
     * The skill proficiencies the request names, once each, when the race, the classes and the background give them
     * all (D232): the race's own and those it lets the player choose, the first class's choices from its list, each
     * later class's multiclassing skill (PHB p.164), and the background's own and those it lets the player choose. A
     * background skill the race or a class gives already is replaced by any other skill (PHB p.125, D226). Each skill
     * must fill a place one of those gives, so a request can't hold more skills than they give together.
     *
     * @param classes the character's classes, the first class first
     * @throws ValidationException naming the skills nothing gives
     * @throws ConflictException   when the reference data can't be read (D155)
     */
    static List<String> skillProficiencies(ObjectMapper objectMapper, Race race, List<CharacterClass> classes,
                                           Background background, List<String> requested) {
        List<String> wanted = distinct(requested).stream().map(CreationChecks::canonicalSkill).toList();
        wanted = distinct(wanted);
        if (wanted.isEmpty()) return requested == null ? null : List.of();
        List<Place> places = new ArrayList<>();
        if (race != null) raceSkillPlaces(objectMapper, race, places);
        for (int i = 0; i < classes.size(); i++) {
            CharacterClass characterClass = classes.get(i);
            if (characterClass == null) continue;
            if (i == 0) {
                JsonNode choices = read(objectMapper, characterClass.getSkillChoices(),
                        "skill choices of the class " + characterClass.getName());
                choicePlaces(choices, places);
            } else {
                JsonNode multiclass = read(objectMapper, characterClass.getMulticlassProficiencies(),
                        "multiclassing proficiencies of the class " + characterClass.getName());
                if (multiclass != null) choicePlaces(multiclass.path("skills"), places);
            }
        }
        if (background != null) backgroundSkillPlaces(objectMapper, background, places);

        Set<String> wantedKeys = keys(wanted);
        List<String> unplaced = unplaced(wanted, places, wantedKeys);
        if (!unplaced.isEmpty()) {
            throw new ValidationException("Proficiency in " + String.join(", ", unplaced) + " isn't one the race, "
                    + "classes and background give (" + places.size() + " skill" + (places.size() == 1 ? "" : "s")
                    + " in all)");
        }
        return wanted;
    }

    /**
     * A place for one skill proficiency: one of the skills it names, or any skill when it names none. A background's
     * own skill takes any other skill too when the request holds that skill from elsewhere (PHB p.125, D226).
     */
    private record Place(Set<String> options, String backgroundSkill) {
        boolean takes(String skill, Set<String> wanted) {
            String k = key(skill);
            if (backgroundSkill != null) {
                return backgroundSkill.equals(k) || (wanted.contains(backgroundSkill) && isSkill(skill));
            }
            return options == null ? isSkill(skill) : options.contains(k);
        }
    }

    private static void raceSkillPlaces(ObjectMapper objectMapper, Race race, List<Place> places) {
        JsonNode proficiencies = read(objectMapper, race.getProficiencies(), "proficiencies of the race "
                + race.getName());
        if (proficiencies != null) {
            for (JsonNode skill : proficiencies.path("skills")) {
                String name = skill.asString("").trim();
                if (!name.isEmpty()) places.add(new Place(Set.of(key(name)), null));
            }
        }
        JsonNode choices = read(objectMapper, race.getRaceChoices(), "choices of the race " + race.getName());
        if (choices == null) return;
        for (JsonNode choice : choices.path("skills")) {
            if (choice.has("any")) {
                addPlaces(places, choice.path("any").asInt(0), null);
            }
            if (choice.has("choose")) {
                choicePlaces(choice.path("choose"), places);
            }
        }
    }

    private static void backgroundSkillPlaces(ObjectMapper objectMapper, Background background, List<Place> places) {
        JsonNode skills = read(objectMapper, background.getSkillProficiencies(),
                "skill proficiencies of the background " + background.getName());
        if (skills == null || !skills.isArray()) return;
        for (JsonNode entry : skills) {
            if (entry.isString()) {
                String name = entry.asString("").trim();
                if (name.isEmpty()) continue;
                if (name.equalsIgnoreCase("Any")) {
                    addPlaces(places, 1, null);
                } else {
                    places.add(new Place(null, key(name)));
                }
            } else if (entry.has("any")) {
                addPlaces(places, entry.path("any").asInt(0), null);
            } else if (entry.has("choose")) {
                choicePlaces(entry.path("choose"), places);
            } else if (entry.has("chooseSet")) {
                // One of several sets: as many places as the largest set, each taking a skill of any set
                Set<String> options = new HashSet<>();
                int largest = 0;
                for (JsonNode set : entry.path("chooseSet")) {
                    int size = 0;
                    for (JsonNode skill : set) {
                        if (skill.isString()) {
                            options.add(key(skill.asString("")));
                            size++;
                        }
                    }
                    largest = Math.max(largest, size);
                }
                addPlaces(places, largest, options);
            }
        }
    }

    // {"count": N, "from": [...]}: N places, each taking one of the skills listed
    private static void choicePlaces(JsonNode choice, List<Place> places) {
        if (choice == null || !choice.isObject()) return;
        List<String> from = new ArrayList<>();
        choice.path("from").forEach(skill -> from.add(skill.asString("")));
        if (from.isEmpty()) return;
        boolean any = from.stream().anyMatch(skill -> skill.trim().equalsIgnoreCase("Any"));
        addPlaces(places, choice.path("count").asInt(1), any ? null : keys(from));
    }

    private static void addPlaces(List<Place> places, int count, Set<String> options) {
        for (int i = 0; i < count; i++) places.add(new Place(options, null));
    }

    // The skills no place can be found for, with each place filled once (a bipartite matching by augmenting paths)
    private static List<String> unplaced(List<String> wanted, List<Place> places, Set<String> wantedKeys) {
        int[] placedIn = new int[places.size()];
        Arrays.fill(placedIn, -1);
        List<String> unplaced = new ArrayList<>();
        for (int s = 0; s < wanted.size(); s++) {
            if (!place(s, wanted, places, wantedKeys, placedIn, new boolean[places.size()])) {
                unplaced.add(wanted.get(s));
            }
        }
        return unplaced;
    }

    private static boolean place(int skill, List<String> wanted, List<Place> places, Set<String> wantedKeys,
                                 int[] placedIn, boolean[] seen) {
        for (int p = 0; p < places.size(); p++) {
            if (seen[p] || !places.get(p).takes(wanted.get(skill), wantedKeys)) continue;
            seen[p] = true;
            if (placedIn[p] < 0 || place(placedIn[p], wanted, places, wantedKeys, placedIn, seen)) {
                placedIn[p] = skill;
                return true;
            }
        }
        return false;
    }

    private static boolean isSkill(String name) {
        return ProficiencyLists.SKILLS.stream().anyMatch(skill -> skill.equalsIgnoreCase(name.trim()));
    }

    // A skill as the PHB names it, "Sleight of Hand" for "sleight of hand"; any other name as given, trimmed
    private static String canonicalSkill(String name) {
        return ProficiencyLists.SKILLS.stream().filter(skill -> skill.equalsIgnoreCase(name.trim())).findFirst()
                .orElse(name.trim());
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static JsonNode read(ObjectMapper objectMapper, String json, String what) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new ConflictException("The " + what + " can't be read");
        }
    }

    // The names given, trimmed, each once whatever its case
    private static List<String> distinct(List<String> names) {
        if (names == null) return List.of();
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) distinct.putIfAbsent(key(name), name.trim());
        }
        return new ArrayList<>(distinct.values());
    }

    private static Set<String> keys(List<String> names) {
        Set<String> keys = new HashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) keys.add(key(name));
        }
        return keys;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
