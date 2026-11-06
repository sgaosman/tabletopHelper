package com.tabletophelper.rules.character;

import com.tabletophelper.rules.Ability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A feat's prerequisites (PHB p.165) and whether a character meets them (D159). A feat's prerequisite is a list of
 * alternatives, any one of which suffices; an alternative lists requirements, all of which are needed. The scores are
 * those the sheet shows, with the DM's adjustments; "the ability to cast at least one spell" counts race and feat
 * spells, while "the Spellcasting or Pact Magic feature" counts only classes; a campaign or a requirement in words is
 * shown but never refused, since only the DM can judge it.
 */
public final class FeatPrerequisites {

    private FeatPrerequisites() {
    }

    /**
     * One way to meet a feat's prerequisite: every requirement it names. A requirement it doesn't name is null or
     * empty.
     *
     * @param abilities           ability minimums, any one of which suffices, as Ritual Caster's Intelligence or
     *                            Wisdom 13
     * @param races               races, any one of which suffices
     * @param spellcasting        the ability to cast at least one spell, from a class, a race or a feat
     * @param spellcastingFeature the Spellcasting or Pact Magic feature of a class
     * @param proficiencies       proficiencies, all of which are needed
     * @param feats               feats, all of which are needed
     * @param level               a minimum character level, or class level when it names a class
     * @param backgrounds         backgrounds, any one of which suffices
     * @param campaigns           campaigns the feat belongs to: shown, never refused
     * @param other               a requirement in words: shown, never refused
     */
    public record PrerequisiteAlternative(List<AbilityMinimum> abilities, List<RaceRequirement> races,
                                          boolean spellcasting, boolean spellcastingFeature,
                                          List<ProficiencyRequirement> proficiencies, List<FeatRequirement> feats,
                                          LevelRequirement level, List<String> backgrounds, List<String> campaigns,
                                          String other) {

        public PrerequisiteAlternative {
            abilities = listOf(abilities);
            races = listOf(races);
            proficiencies = listOf(proficiencies);
            feats = listOf(feats);
            backgrounds = listOf(backgrounds);
            campaigns = listOf(campaigns);
        }
    }

    /** An ability score of at least the minimum, as Grappler's Strength 13. */
    public record AbilityMinimum(Ability ability, int minimum) {
    }

    /**
     * A race, or a subrace of it, as Drow High Magic's elf (drow).
     *
     * @param name    the race, in the data's words, such as "elf", "half-orc" or "small race"
     * @param subrace the subrace, such as "drow"; null for any
     * @param display how the data shows it, such as "a Small race"; null to show the name
     */
    public record RaceRequirement(String name, String subrace, String display) {
    }

    /** What a proficiency requirement names: armour, a weapon or a weapon group. */
    public enum ProficiencyKind { ARMOR, WEAPON, WEAPON_GROUP }

    /**
     * A proficiency, as Heavily Armored's medium armour or Fighting Initiate's martial weapons.
     *
     * @param name such as "medium" or "martial"
     */
    public record ProficiencyRequirement(ProficiencyKind kind, String name) {
    }

    /**
     * A feat.
     *
     * @param name    the feat, such as "Strike of the Giants"
     * @param source  its book, such as "BGG"; null when the data doesn't say
     * @param display the data's wording, which can name an option of the feat, such as "Strike of the Giants (Fire
     *                Strike)"; the option isn't checked, since the sheet doesn't record it
     */
    public record FeatRequirement(String name, String source, String display) {
    }

    /**
     * A minimum level.
     *
     * @param className the class it is a level in, such as "Sorcerer"; null for the character level
     */
    public record LevelRequirement(int level, String className) {
    }

    /**
     * What a character brings to a feat's prerequisites.
     *
     * @param scores              each ability score as the sheet shows it, with the DM's adjustments
     * @param race                the race's name, such as "Drow Elf"; null for none
     * @param baseRace            the race a subrace belongs to, such as "Elf"; null for a race without one
     * @param raceSize            the race's size, such as "Small"; null when unknown
     * @param castsSpell          whether it can cast at least one spell: a class's, a race's or a feat's
     * @param spellcastingFeature whether one of its classes gives it the Spellcasting or Pact Magic feature
     * @param armour              its armour proficiencies, such as "light" or "Medium armor"
     * @param weapons             its weapon proficiencies, such as "simple", "martial" or "longsword"
     * @param feats               the names of the feats it has, in any case
     * @param level               its character level
     * @param classLevels         its level in each class, by name
     * @param background          its background's name; null for none
     */
    public record Facts(Map<Ability, Integer> scores, String race, String baseRace, String raceSize,
                        boolean castsSpell, boolean spellcastingFeature, List<String> armour, List<String> weapons,
                        Set<String> feats, int level, Map<String, Integer> classLevels, String background) {

        public Facts {
            scores = scores != null ? Map.copyOf(scores) : Map.of();
            armour = listOf(armour);
            weapons = listOf(weapons);
            feats = feats != null ? feats.stream().filter(Objects::nonNull).map(FeatPrerequisites::key)
                    .collect(Collectors.toUnmodifiableSet()) : Set.of();
            classLevels = classLevels != null ? Map.copyOf(classLevels) : Map.of();
        }
    }

    // ── Meeting them ─────────────────────────────────────────────

    /**
     * What the character doesn't meet of the prerequisite, in words; empty when it meets one of its alternatives, or
     * when the feat has none. A campaign or a requirement in words is never listed. With one alternative, each
     * requirement it misses is an entry; with more, one entry names what each would need, joined by "or".
     */
    public static List<String> unmet(List<PrerequisiteAlternative> prerequisite, Facts facts) {
        List<PrerequisiteAlternative> alternatives = listOf(prerequisite);
        if (alternatives.isEmpty()) return List.of();
        List<List<String>> missing = new ArrayList<>();
        for (PrerequisiteAlternative alternative : alternatives) {
            List<String> unmet = unmet(alternative, facts);
            if (unmet.isEmpty()) return List.of();
            missing.add(unmet);
        }
        if (missing.size() == 1) return missing.get(0);
        return List.of(joined(missing.stream().map(unmet -> joined(unmet, " and ")).toList(), ", or "));
    }

    /** Whether the character meets the prerequisite. */
    public static boolean meets(List<PrerequisiteAlternative> prerequisite, Facts facts) {
        return unmet(prerequisite, facts).isEmpty();
    }

    // Each requirement of one alternative the character misses, in words
    private static List<String> unmet(PrerequisiteAlternative alternative, Facts facts) {
        List<String> unmet = new ArrayList<>();
        if (!alternative.abilities().isEmpty() && alternative.abilities().stream().noneMatch(a -> meets(a, facts))) {
            String has = alternative.abilities().stream()
                    .map(a -> String.valueOf(facts.scores().getOrDefault(a.ability(), 0)))
                    .collect(Collectors.joining(" and "));
            unmet.add(abilities(alternative.abilities()) + " (you have " + has + ")");
        }
        if (!alternative.races().isEmpty() && alternative.races().stream().noneMatch(r -> meets(r, facts))) {
            unmet.add(races(alternative.races()) + " (you are " + (facts.race() != null ? article(facts.race())
                    : "of no race") + ")");
        }
        if (alternative.spellcasting() && !facts.castsSpell()) {
            unmet.add(SPELLCASTING);
        }
        if (alternative.spellcastingFeature() && !facts.spellcastingFeature()) {
            unmet.add(SPELLCASTING_FEATURE);
        }
        for (ProficiencyRequirement proficiency : alternative.proficiencies()) {
            if (!meets(proficiency, facts)) {
                unmet.add(proficiency(proficiency));
            }
        }
        for (FeatRequirement feat : alternative.feats()) {
            if (!facts.feats().contains(key(feat.name()))) {
                unmet.add(feat(feat));
            }
        }
        LevelRequirement level = alternative.level();
        if (level != null) {
            if (level.className() == null) {
                if (facts.level() < level.level()) {
                    unmet.add(level(level) + " (you are level " + facts.level() + ")");
                }
            } else {
                int classLevel = classLevel(facts, level.className());
                if (classLevel < level.level()) {
                    unmet.add(level(level) + " (you have " + classLevel + " " + level.className() + " level"
                            + (classLevel == 1 ? "" : "s") + ")");
                }
            }
        }
        if (!alternative.backgrounds().isEmpty() && alternative.backgrounds().stream()
                .noneMatch(b -> facts.background() != null && key(b).equals(key(facts.background())))) {
            unmet.add(backgrounds(alternative.backgrounds()));
        }
        return unmet;
    }

    private static boolean meets(AbilityMinimum minimum, Facts facts) {
        return facts.scores().getOrDefault(minimum.ability(), 0) >= minimum.minimum();
    }

    /**
     * A race requirement is met by that race, by a subrace of it, or by a race of that name from another world, such
     * as "Elf (Kaladesh)" for an elf; a subrace requirement by a race of that base whose name also names the subrace,
     * such as "Drow Elf" or "Gnome (Deep)". "Small race" is met by any Small race.
     */
    static boolean meets(RaceRequirement requirement, Facts facts) {
        String wanted = withoutWorld(requirement.name());
        if ("small race".equals(wanted)) {
            return facts.raceSize() != null && key(facts.raceSize()).startsWith("small");
        }
        if (facts.race() == null) return false;
        String race = key(facts.race());
        String base = facts.baseRace() != null ? withoutWorld(facts.baseRace()) : null;
        boolean ofRace = wanted.equals(withoutWorld(race)) || wanted.equals(base);
        if (!ofRace) return false;
        if (requirement.subrace() == null || requirement.subrace().isBlank()) return true;
        String subrace = key(requirement.subrace());
        return words(race).contains(subrace);
    }

    private static boolean meets(ProficiencyRequirement requirement, Facts facts) {
        List<String> held = requirement.kind() == ProficiencyKind.ARMOR ? facts.armour() : facts.weapons();
        String wanted = proficiencyKey(requirement.name());
        return held.stream().anyMatch(p -> proficiencyKey(p).equals(wanted));
    }

    private static int classLevel(Facts facts, String className) {
        return facts.classLevels().entrySet().stream()
                .filter(e -> e.getKey() != null && key(e.getKey()).equals(key(className)))
                .mapToInt(Map.Entry::getValue).sum();
    }

    // ── In words ─────────────────────────────────────────────────

    private static final String SPELLCASTING = "The ability to cast at least one spell";
    private static final String SPELLCASTING_FEATURE = "The Spellcasting or Pact Magic feature";

    /**
     * The prerequisite in words, every requirement included, campaigns and those in words too; null when the feat has
     * none. Alternatives are joined by "or".
     */
    public static String describe(List<PrerequisiteAlternative> prerequisite) {
        List<PrerequisiteAlternative> alternatives = listOf(prerequisite);
        if (alternatives.isEmpty()) return null;
        return joined(alternatives.stream().map(FeatPrerequisites::describe).toList(), "; or ");
    }

    private static String describe(PrerequisiteAlternative alternative) {
        List<String> parts = new ArrayList<>();
        alternative.campaigns().forEach(campaign -> parts.add("A " + campaign + " campaign"));
        if (alternative.level() != null) parts.add(level(alternative.level()));
        if (!alternative.abilities().isEmpty()) parts.add(abilities(alternative.abilities()));
        if (!alternative.races().isEmpty()) parts.add(races(alternative.races()));
        if (alternative.spellcasting()) parts.add(SPELLCASTING);
        if (alternative.spellcastingFeature()) parts.add(SPELLCASTING_FEATURE);
        alternative.proficiencies().forEach(p -> parts.add(proficiency(p)));
        alternative.feats().forEach(f -> parts.add(feat(f)));
        if (!alternative.backgrounds().isEmpty()) parts.add(backgrounds(alternative.backgrounds()));
        if (alternative.other() != null && !alternative.other().isBlank()) parts.add(alternative.other().trim());
        return joined(parts, ", ");
    }

    // The parts joined, each after the first starting in lower case where it starts with an article or such a word,
    // so "4th level, the Squire of Solamnia feat" reads as one phrase
    private static String joined(List<String> parts, String separator) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (i > 0) {
                joined.append(separator);
                part = lowerFirstWord(part);
            }
            joined.append(part);
        }
        return joined.toString();
    }

    private static String lowerFirstWord(String part) {
        for (String word : List.of("The ", "A ", "An ", "Being ", "Proficiency ")) {
            if (part.startsWith(word)) {
                return word.toLowerCase(Locale.ROOT) + part.substring(word.length());
            }
        }
        return part;
    }

    // "Strength 13", or "Intelligence or Wisdom 13", or "Strength 13 or Dexterity 15"
    private static String abilities(List<AbilityMinimum> abilities) {
        boolean sameMinimum = abilities.stream().map(AbilityMinimum::minimum).distinct().count() == 1;
        if (sameMinimum) {
            return abilities.stream().map(a -> capitalised(a.ability().fullName())).collect(Collectors.joining(" or "))
                    + " " + abilities.get(0).minimum();
        }
        return abilities.stream().map(a -> capitalised(a.ability().fullName()) + " " + a.minimum())
                .collect(Collectors.joining(" or "));
    }

    private static String races(List<RaceRequirement> races) {
        return "Being " + races.stream().map(FeatPrerequisites::race).collect(Collectors.joining(" or "));
    }

    // "an elf (drow)", "a half-orc", "a Small race"
    private static String race(RaceRequirement race) {
        if (race.display() != null && !race.display().isBlank()) return race.display().trim();
        String name = race.name().trim();
        String named = race.subrace() != null && !race.subrace().isBlank()
                ? name + " (" + race.subrace().trim() + ")" : name;
        return article(named);
    }

    private static String proficiency(ProficiencyRequirement proficiency) {
        String name = proficiency.name().trim().toLowerCase(Locale.ROOT);
        return switch (proficiency.kind()) {
            case ARMOR -> "Proficiency with " + name + " armor";
            case WEAPON, WEAPON_GROUP -> "Proficiency with " + (name.equals("martial") || name.equals("simple")
                    ? name + " weapons" : name);
        };
    }

    private static String feat(FeatRequirement feat) {
        String shown = feat.display() != null && !feat.display().isBlank() ? feat.display() : feat.name();
        return "The " + titled(shown) + " feat";
    }

    // "4th level", or "1st-level Sorcerer"
    private static String level(LevelRequirement level) {
        String ordinal = ordinal(level.level());
        return level.className() == null ? ordinal + " level" : ordinal + "-level " + level.className();
    }

    private static String backgrounds(List<String> backgrounds) {
        return "The " + String.join(" or ", backgrounds) + " background";
    }

    private static String ordinal(int n) {
        int lastTwo = n % 100;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    private static String article(String noun) {
        String trimmed = noun.trim();
        if (trimmed.isEmpty()) return trimmed;
        boolean vowel = "aeiou".indexOf(Character.toLowerCase(trimmed.charAt(0))) >= 0;
        return (vowel ? "an " : "a ") + trimmed;
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    // Each word capitalised, words in brackets too: "strike of the giants (fire strike)" → "Strike Of The Giants
    // (Fire Strike)", with the short words lower case
    private static String titled(String text) {
        Set<String> small = Set.of("of", "the", "and", "a", "an", "in", "on");
        String[] words = text.trim().split("\\s+");
        StringBuilder titled = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            String bare = word.startsWith("(") ? word.substring(1) : word;
            String prefix = word.startsWith("(") ? "(" : "";
            String lower = bare.toLowerCase(Locale.ROOT);
            String shown = i > 0 && prefix.isEmpty() && small.contains(lower) ? lower : capitalised(bare);
            if (i > 0) titled.append(' ');
            titled.append(prefix).append(shown);
        }
        return titled.toString();
    }

    // ── Matching ─────────────────────────────────────────────────

    // Lower case and trimmed, so names match in any case
    static String key(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    // A name without its world, as "Elf (Kaladesh)" or "Vampire (Ixalan)" give "elf" and "vampire"
    private static String withoutWorld(String name) {
        return key(name).replaceAll("\\s*\\([^)]*\\)\\s*$", "");
    }

    // The words of a race's name, brackets and slashes splitting them, as "Deep/Svirfneblin Gnome" gives deep,
    // svirfneblin and gnome
    private static Set<String> words(String name) {
        return Set.of(key(name).split("[\\s/();,]+")).stream().filter(w -> !w.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // "Medium armor", "medium" and "Medium Armour" alike; "Martial weapons" and "martial" alike
    private static String proficiencyKey(String proficiency) {
        return key(proficiency).replaceAll("\\s+(armor|armour|weapons?)$", "");
    }

    private static <T> List<T> listOf(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
